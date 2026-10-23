package kr.co.cking.quiz.application;

import jakarta.persistence.EntityManagerFactory;
import kr.co.cking.quiz.application.config.QuizJudgeEvaluationProperties;
import kr.co.cking.quiz.domain.QuizJudgeJob;
import kr.co.cking.quiz.domain.QuizJudgeJobStatus;
import kr.co.cking.quiz.domain.QuizJudgeFailure;
import kr.co.cking.quiz.judge.QuizJudge;
import kr.co.cking.quiz.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.Map;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 실제 V58의 Job 테이블·인덱스와 Store를 사용한다. 다른 도메인의 FK 대상은 이 테스트에서 생략한다. */
@Testcontainers
class QuizJudgeStaleLockTest {
    private static final Instant NOW = Instant.parse("2026-10-23T07:35:11Z");
    @Container
    static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4.10")
            .withEnv("MYSQL_ROOT_PASSWORD", "test").withEnv("MYSQL_DATABASE", "judge_lock")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci")
            .withExposedPorts(3306)
            .waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(3)));
    private static String url;
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static QuizJudgeEvaluationStore store;

    @BeforeAll
    static void setup() throws Exception {
        assertThat(MYSQL.getMappedPort(3306)).isNotEqualTo(3306);
        url = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
                + "/judge_lock?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        String migration = new ClassPathResource("db/migration/V58__add_quiz_judge.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        String table = migration.substring(migration.indexOf("CREATE TABLE quiz_judge_job ("));
        table = table.substring(0, table.indexOf(';') + 1)
                .replaceAll("(?s),\\s*CONSTRAINT fk_quiz_judge_job_\\w+ FOREIGN KEY.*?REFERENCES \\w+ \\([^)]*\\)", "");
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute(table);
        }
        context = new AnnotationConfigApplicationContext(Config.class);
        jdbc = context.getBean(JdbcTemplate.class);
        store = context.getBean(QuizJudgeEvaluationStore.class);
    }

    @AfterAll
    static void close() { if (context != null) context.close(); }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM quiz_judge_job");
        for (long id = 100; id < 130; id++) insert(id, id, "SUCCEEDED", NOW.minusSeconds(3600), NOW.minusSeconds(3500));
    }

    @Test
    void 만료대상이_없으면_Worker가_잠근_RUNNING행과_상태인덱스를_잠그지_않는다() throws Exception {
        insert(11, 10, "RUNNING", NOW.minusSeconds(1800), NOW.minusSeconds(30));
        try (Connection worker = connection()) {
            worker.setAutoCommit(false);
            try (Statement statement = worker.createStatement()) {
                statement.executeQuery("SELECT job_id FROM quiz_judge_job WHERE job_id=11 FOR UPDATE").close();
            }
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> cleanup = executor.submit(() -> store.expireStale(10L, NOW));
            try {
                // 기존 범위 UPDATE는 RUNNING 인덱스 경계까지 잠그고 이 PK 잠금을 기다린다.
                cleanup.get(3, TimeUnit.SECONDS);
                try (Statement statement = worker.createStatement()) {
                    statement.executeUpdate("UPDATE quiz_judge_job SET status='SUCCEEDED',completed_at=UTC_TIMESTAMP(6) WHERE job_id=11");
                }
                worker.commit();
            } finally {
                worker.rollback();
                executor.shutdown();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
        }
        assertThat(status(11)).isEqualTo("SUCCEEDED");
    }

    @Test
    void 오래된_PENDING과_RUNNING만_기존정책대로_정리한다() {
        insert(1, 10, "PENDING", NOW.minusSeconds(1800), null);
        insert(2, 20, "RUNNING", NOW.minusSeconds(1800), NOW.minusSeconds(600));
        insert(3, 30, "PENDING", NOW.minusSeconds(30), null);
        insert(4, 40, "RUNNING", NOW.minusSeconds(1800), NOW.minusSeconds(30));
        for (long creator : new long[]{10, 20, 30, 40}) store.expireStale(creator, NOW);
        assertThat(status(1)).isEqualTo("FAILED");
        assertThat(status(2)).isEqualTo("UNKNOWN");
        assertThat(status(3)).isEqualTo("PENDING");
        assertThat(status(4)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT failure_code FROM quiz_judge_job WHERE job_id=1", String.class)).isEqualTo("STALE_PENDING");
        assertThat(jdbc.queryForObject("SELECT failure_code FROM quiz_judge_job WHERE job_id=2", String.class)).isEqualTo("STALE_RUNNING");
        store.expireStale(10L, NOW);
        store.expireStale(20L, NOW);
        assertThat(status(1)).isEqualTo("FAILED");
        assertThat(status(2)).isEqualTo("UNKNOWN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "RUNNING"})
    void 후보조회_이후_완료된_작업을_만료로_덮어쓰지_않는다(String status) {
        insert(11, 10, status, NOW.minusSeconds(1800), status.equals("RUNNING") ? NOW.minusSeconds(600) : null);
        var jobs = context.getBean(QuizJudgeJobRepository.class);
        var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        transaction.executeWithoutResult(ignored -> {
            Instant before = NOW.minusSeconds(status.equals("PENDING") ? 900 : 300);
            var ids = status.equals("PENDING")
                    ? jobs.findPendingToExpire(10L, before, QuizJudgeJobStatus.PENDING)
                    : jobs.findRunningToExpire(10L, before, QuizJudgeJobStatus.RUNNING);
            assertThat(ids).containsExactly(11L);
            try (Connection worker = connection(); Statement statement = worker.createStatement()) {
                statement.executeUpdate("UPDATE quiz_judge_job SET status='SUCCEEDED',started_at=COALESCE(started_at,UTC_TIMESTAMP(6)),"
                        + "completed_at=UTC_TIMESTAMP(6) WHERE job_id=11");
            } catch (SQLException exception) {
                throw new IllegalStateException(exception);
            }
            int changed = status.equals("PENDING")
                    ? jobs.expirePending(11L, before, QuizJudgeJobStatus.PENDING, QuizJudgeJobStatus.FAILED,
                    QuizJudgeFailure.STALE_PENDING, NOW)
                    : jobs.expireRunning(11L, before, QuizJudgeJobStatus.RUNNING, QuizJudgeJobStatus.UNKNOWN,
                    QuizJudgeFailure.STALE_RUNNING, NOW);
            assertThat(changed).isZero();
        });
        assertThat(status(11)).isEqualTo("SUCCEEDED");
        String timeColumn = status.equals("PENDING") ? "requested_at" : "started_at";
        var plan = jdbc.queryForList("EXPLAIN UPDATE quiz_judge_job SET status='FAILED',failure_code='STALE_PENDING',"
                + "completed_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6) WHERE job_id=11 AND status='"
                + status + "' AND " + timeColumn + "<'2026-10-23 07:30:11'");
        assertThat(plan.getFirst().get("key")).isEqualTo("PRIMARY");
    }

    private static void insert(long id, long creator, String status, Instant requested, Instant started) {
        jdbc.update("INSERT INTO quiz_judge_job(job_id,series_id,quiz_id,revision_id,creator_id,requested_by_member_id,"
                        + "request_id,attempt_no,status,requested_at,started_at,completed_at,updated_at) VALUES(?,?,?,?,?,1,UUID(),1,?,?,?,?,?)",
                id, id, id, id, creator, status, Timestamp.from(requested), started == null ? null : Timestamp.from(started),
                status.equals("SUCCEEDED") ? Timestamp.from(NOW) : null, Timestamp.from(NOW));
    }

    private static String status(long id) { return jdbc.queryForObject("SELECT status FROM quiz_judge_job WHERE job_id=?", String.class, id); }

    private static Connection connection() throws SQLException {
        if (!MYSQL.isRunning() || MYSQL.getMappedPort(3306) == 3306) throw new IllegalStateException("Dedicated MySQL required");
        return DriverManager.getConnection(url, "root", "test");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() { return new DriverManagerDataSource(url, "root", "test"); }
        @Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(ds);
            factory.setManagedTypes(PersistenceManagedTypes.of(QuizJudgeJob.class.getName()));
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
            return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory emf, DataSource ds) {
            var manager = new JpaTransactionManager(emf);
            manager.setDataSource(ds);
            return manager;
        }
        @Bean QuizJudgeJobRepository jobs(EntityManagerFactory emf) {
            return new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(
                    SharedEntityManagerCreator.createSharedEntityManager(emf)).getRepository(QuizJudgeJobRepository.class);
        }
        @Bean QuizJudgeEvaluationStore store(QuizJudgeJobRepository jobs) {
            return new QuizJudgeEvaluationStore(mock(QuizRepository.class), mock(QuizRevisionRepository.class),
                    mock(QuizRevisionDecisionRepository.class), mock(QuizRevisionReader.class), mock(QuizJudgeSeriesRepository.class),
                    jobs, mock(QuizJudgeJobCallRepository.class), mock(QuizJudgeStoredResultRepository.class),
                    mock(QuizJudgeStoredResultItemRepository.class), mock(QuizJudge.class),
                    new QuizJudgeEvaluationProperties(), Clock.fixed(NOW, ZoneOffset.UTC));
        }
    }
}
