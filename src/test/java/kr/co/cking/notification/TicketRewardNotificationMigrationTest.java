package kr.co.cking.notification;

import jakarta.persistence.EntityManagerFactory;
import kr.co.cking.notification.application.TicketRewardNotificationService;
import kr.co.cking.notification.domain.Notification;
import kr.co.cking.notification.repository.NotificationRepository;
import kr.co.cking.ticket.application.RewardNotificationLedgerStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** V90과 실제 Service/JPA/JDBC 트랜잭션만 검증한다. Boot 애플리케이션은 시작하지 않는다. */
@Testcontainers
class TicketRewardNotificationMigrationTest {
    @Container static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4.10")
            .withEnv("MYSQL_ROOT_PASSWORD", "test").withEnv("MYSQL_DATABASE", "reward_migration")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci")
            .withExposedPorts(3306).waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306.*",1)
                    .withStartupTimeout(Duration.ofMinutes(3)));
    static String url;
    static AnnotationConfigApplicationContext context;
    static JdbcTemplate jdbc;
    static RewardNotificationLedgerStore store;
    static TicketRewardNotificationService service;
    static NotificationRepository notifications;

    @BeforeAll static void migrate() throws Exception {
        assertThat(MYSQL.getMappedPort(3306)).isNotEqualTo(3306);
        url="jdbc:mysql://"+MYSQL.getHost()+":"+MYSQL.getMappedPort(3306)+"/reward_migration?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        Flyway.configure().dataSource(url,"root","test").locations("classpath:db/migration").target("89").load().migrate();
        try(Connection c=connection(); Statement s=c.createStatement()) {
            s.execute("INSERT INTO member(member_id,name,role) VALUES(1,'probe','USER')");
            s.execute("INSERT INTO creator(creator_id,member_id,name,activity_name,activity_name_normalized) VALUES(1,1,'probe','이름','이름')");
            s.execute("INSERT INTO mission(mission_id,creator_id,type,reward_amount) VALUES(1,1,'LIKE',1)");
            s.execute("INSERT INTO mission_completion(completion_id,member_id,creator_id,mission_id,period_key,completion_key,request_id,payload_fingerprint,completed_at) VALUES(1,1,1,1,'2026-10-21','2026-10-21',UUID(),REPEAT('a',64),UTC_TIMESTAMP(6))");
            s.execute("INSERT INTO common_mission_completion(completion_id,member_id,mission_id,period_key,request_id,payload_fingerprint,completed_at) SELECT 1,1,mission_id,'2026-10-21',UUID(),REPEAT('a',64),UTC_TIMESTAMP(6) FROM common_mission WHERE type='ATTENDANCE'");
            s.execute("INSERT INTO ticket_ledger(member_id,creator_id,mission_completion_id,delta_amount,type,request_id) VALUES(1,1,1,2,'EARN',UUID())");
            s.execute("INSERT INTO common_ticket_ledger(member_id,mission_completion_id,delta_amount,type,request_id) VALUES(1,1,2,'EARN',UUID())");
        }
        var result=Flyway.configure().dataSource(url,"root","test").locations("classpath:db/migration").target("90").load().migrate();
        assertThat(result.migrationsExecuted).isEqualTo(1);
        context=new AnnotationConfigApplicationContext(Config.class);
        jdbc=context.getBean(JdbcTemplate.class);
        store=context.getBean(RewardNotificationLedgerStore.class);
        service=context.getBean(TicketRewardNotificationService.class);
        notifications=context.getBean(NotificationRepository.class);
        assertThat(jdbc.queryForObject("SELECT reward_notification_state FROM ticket_ledger WHERE ledger_id=1",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT reward_notification_state FROM common_ticket_ledger WHERE ledger_id=1",Integer.class)).isZero();
    }

    @AfterAll static void close() { if(context!=null) context.close(); }

    @BeforeEach void clean() {
        for(boolean common:new boolean[]{false,true}) jdbc.update("UPDATE "+table(common)+" SET reward_notification_state=0,reward_notification_id=NULL");
        jdbc.update("DELETE FROM notification");
        for(boolean common:new boolean[]{false,true}) jdbc.update("DELETE FROM "+table(common)+" WHERE ledger_id<>1");
        clearInvocations(jdbc);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void 최종_migration과_실제_Service로_생성하고_기존행은_제외한다(boolean common) {
        long id=earn(common,3);
        assertThat(store.findDue(common,20)).containsExactly(id);
        service.create(common,id);
        Notification n=find(common,id);
        assertThat(n.getBody()).isEqualTo((common?"출석체크 보상으로 공용":"좋아요 보상으로 이름 크리에이터 전용")+" 응모권 3장이 지급됐어요.");
        assertThat(n.getEventId()).isNull();
        assertThat(state(common,id)).isEqualTo(2);
        assertThat(store.findDue(common,20)).isEmpty();
        assertThat(state(common,1)).isZero();
        service.create(common,id);
        assertThat(count(common,id)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void 실패_롤백_예약_복구와_숨긴_기존_알림_재사용(boolean common) {
        long id=earn(common,2);
        String table=table(common);
        jdbc.execute("CREATE TRIGGER reward_probe_failure BEFORE UPDATE ON "+table+" FOR EACH ROW BEGIN IF NEW.reward_notification_state=2 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='probe failure'; END IF; END");
        try { assertThatThrownBy(()->service.create(common,id)).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("DROP TRIGGER reward_probe_failure"); }
        assertThat(state(common,id)).isEqualTo(1);
        assertThat(count(common,id)).isZero();
        service.retry(common,id);
        assertThat(store.findDue(common,20)).isEmpty();
        jdbc.update("UPDATE "+table+" SET reward_notification_retry_at='1970-01-01' WHERE ledger_id=?",id);
        service.create(common,id);
        long notice=find(common,id).getNotificationId();
        jdbc.update("UPDATE notification SET hidden_at=UTC_TIMESTAMP(6) WHERE id=?",notice);
        jdbc.update("UPDATE "+table+" SET reward_notification_state=1,reward_notification_id=NULL WHERE ledger_id=?",id);
        service.create(common,id);
        assertThat(find(common,id).getNotificationId()).isEqualTo(notice);
        assertThat(find(common,id).getHiddenAt()).isNotNull();
        assertThat(count(common,id)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void 동시_실Service_트랜잭션도_알림은_한개다(boolean common) throws Exception {
        long id=earn(common,2);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
        Callable<Void> task=()->{ ready.countDown(); if(!start.await(10,TimeUnit.SECONDS)) throw new AssertionError(); service.create(common,id); return null; };
        try {
            var a=pool.submit(task); var b=pool.submit(task);
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); start.countDown();
            a.get(20,TimeUnit.SECONDS); b.get(20,TimeUnit.SECONDS);
        } finally { start.countDown(); pool.shutdownNow(); }
        assertThat(count(common,id)).isEqualTo(1);
        assertThat(state(common,id)).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void 늦게_커밋한_낮은_ID를_찾고_양수가_아니면_제외한다(boolean common) throws Exception {
        try(Connection c=connection();Statement s=c.createStatement()) {
            c.setAutoCommit(false);
            s.executeUpdate(insertSql(common,2),Statement.RETURN_GENERATED_KEYS);
            long low; try(ResultSet keys=s.getGeneratedKeys()) { keys.next(); low=keys.getLong(1); }
            long high=earn(common,2);
            assertThat(store.findDue(common,20)).containsExactly(high);
            service.create(common,high); c.commit();
            assertThat(store.findDue(common,20)).containsExactly(low);
            service.create(common,low);
        }
        long zero=earn(common,0);
        service.create(common,zero);
        assertThat(state(common,zero)).isZero();
        assertThat(count(common,zero)).isZero();
        jdbc.update("INSERT INTO "+table(common)+"(member_id,"+(common?"":"creator_id,")+"delta_amount,type) VALUES(1,"+(common?"":"1,")+"0,'COMPENSATE')");
        assertThat(store.findDue(common,20)).isEmpty();
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void 실제_대상조회_SQL이_완료이력을_제외하는_인덱스를_쓴다(boolean common) {
        for(int i=0;i<10;i++) service.create(common,earn(common,2));
        for(int i=0;i<20;i++) earn(common,2);
        clearInvocations(jdbc);
        assertThat(store.findDue(common,10)).hasSize(10);
        var query=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(query.capture(),eq(Long.class),eq(10));
        String sql=query.getValue().replace("LIMIT ?","LIMIT 10");
        String plan=jdbc.queryForObject("EXPLAIN ANALYZE "+sql,String.class);
        assertThat(plan).contains(common?"idx_reward_common_due":"idx_reward_creator_due");
        System.out.println("FINAL_MIGRATION_PLAN "+table(common)+"\n"+plan);
    }

    @Test void 실제_ONCE와_Quiz_원인도_원장_수량으로_조회한다() {
        jdbc.update("INSERT INTO ticket_common_once_earn_request(request_id,member_id,reward_type,amount,payload_fingerprint,status,created_at,accepted_at) VALUES(UUID(),1,'SIGNUP',1,REPEAT('a',64),'ACCEPTED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        long once=jdbc.queryForObject("SELECT MAX(once_earn_request_id) FROM ticket_common_once_earn_request",Long.class);
        jdbc.update("INSERT INTO common_ticket_ledger(member_id,once_earn_request_id,delta_amount,type,request_id) VALUES(1,?,1,'EARN',UUID())",once);
        long common=jdbc.queryForObject("SELECT MAX(ledger_id) FROM common_ticket_ledger",Long.class);
        service.create(true,common);
        assertThat(find(true,common).getBody()).isEqualTo("웰컴 가입 보상으로 공용 응모권 1장이 지급됐어요.");
        jdbc.update("INSERT INTO ticket_quiz_reward_request(reward_request_id,attempt_id,member_id,creator_id,correct_count,amount,fingerprint_version,payload_fingerprint,status,created_at,applied_at) VALUES(UUID(),1,1,1,3,3,'v1',REPEAT('a',64),'APPLIED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        long quiz=jdbc.queryForObject("SELECT MAX(quiz_reward_request_id) FROM ticket_quiz_reward_request",Long.class);
        jdbc.update("INSERT INTO ticket_ledger(member_id,creator_id,quiz_reward_request_id,delta_amount,type,request_id) SELECT 1,1,quiz_reward_request_id,3,'EARN',reward_request_id FROM ticket_quiz_reward_request WHERE quiz_reward_request_id=?",quiz);
        long creator=jdbc.queryForObject("SELECT MAX(ledger_id) FROM ticket_ledger",Long.class);
        service.create(false,creator);
        assertThat(find(false,creator).getBody()).isEqualTo("AI 퀴즈 보상으로 이름 크리에이터 전용 응모권 3장이 지급됐어요.");
    }

    @Test void 최종_migration의_중복과_잘못된_참조_조합을_차단한다() {
        long first=earn(false,2), second=earn(false,2);
        service.create(false,first);
        long notice=find(false,first).getNotificationId();
        assertDatabaseError(()->jdbc.update("UPDATE ticket_ledger SET reward_notification_state=2 WHERE ledger_id=?",second),3819);
        assertDatabaseError(()->jdbc.update("UPDATE ticket_ledger SET reward_notification_state=2,reward_notification_id=? WHERE ledger_id=?",notice,second),1452);
        assertDatabaseError(()->jdbc.update("INSERT INTO notification(member_id,type,title,body,ticket_ledger_id) VALUES(1,'CREATOR_TICKET_REWARDED','probe','probe',?)",first),1062);
        assertDatabaseError(()->jdbc.update("INSERT INTO notification(member_id,type,title,body,ticket_ledger_id) VALUES(1,'COMMON_TICKET_REWARDED','probe','probe',?)",second),3819);
        assertDatabaseError(()->jdbc.update("DELETE FROM notification WHERE id=?",notice),1451);
    }

    @Test void 새_알림도_기존_조회_읽음_전체읽음_숨김_Service를_사용한다() {
        long creator=earn(false,2), common=earn(true,2);
        service.create(false,creator); service.create(true,common);
        assertThat(notifications.findVisibleByMemberId(1L,org.springframework.data.domain.PageRequest.of(0,20)).getTotalElements()).isEqualTo(2);
        var read=context.getBean(kr.co.cking.notification.application.NotificationReadService.class);
        var hide=context.getBean(kr.co.cking.notification.application.NotificationHideService.class);
        long first=find(false,creator).getNotificationId(), second=find(true,common).getNotificationId();
        read.read(1L,first); read.readAll(1L);
        assertThat(find(false,creator).getReadAt()).isNotNull();
        assertThat(find(true,common).getReadAt()).isNotNull();
        hide.hide(1L,first); hide.hide(1L,second);
        assertThat(notifications.findVisibleByMemberId(1L,org.springframework.data.domain.PageRequest.of(0,20)).getTotalElements()).isZero();
        service.create(false,creator); service.create(true,common);
        assertThat(count(false,creator)+count(true,common)).isEqualTo(2);
    }

    @Test void 부분_DDL_실패_후_원본_migration_재개도_표식을_초기화하지_않는다() throws Exception {
        String partial=url.replace("/reward_migration?","/reward_partial?");
        try(Connection c=connection(); Statement s=c.createStatement()) { s.execute("CREATE DATABASE reward_partial CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"); }
        Flyway.configure().dataSource(partial,"root","test").locations("classpath:db/migration").target("89").load().migrate();
        var resource=new org.springframework.core.io.ClassPathResource("db/migration/V90__add_ticket_reward_notifications.sql");
        String script=resource.getContentAsString(StandardCharsets.UTF_8);
        try(Connection c=DriverManager.getConnection(partial,"root","test"); Statement s=c.createStatement()) {
            String prefix=script.split("-- 지급 알림 참조",2)[0];
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,new org.springframework.core.io.ByteArrayResource(prefix.getBytes(StandardCharsets.UTF_8)));
            assertThatThrownBy(()->s.execute("SELECT * FROM deliberately_missing_table")).isInstanceOf(SQLException.class);
        }
        // 실제 실패 이력 repair 정책은 기존 DB probe에서 검증한다. 여기서는 같은 원본 V90의 부분 스키마 재진입을 검증한다.
        Flyway.configure().dataSource(partial,"root","test").locations("classpath:db/migration").target("90").load().migrate();
        try(Connection c=DriverManager.getConnection(partial,"root","test")) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,resource);
        }
        long pending=earn(false,2);
        service.create(false,pending);
        try(Connection c=connection()) { org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,resource); }
        assertThat(state(false,1)).isZero();
        assertThat(state(false,pending)).isEqualTo(2);
        assertThat(count(false,pending)).isEqualTo(1);
    }

    private static void assertDatabaseError(Runnable action,int expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class,e->{
            assertThat(e.getMostSpecificCause()).isInstanceOf(SQLException.class);
            assertThat(((SQLException)e.getMostSpecificCause()).getErrorCode()).isEqualTo(expected);
        });
    }

    private static long earn(boolean common,long amount) { jdbc.update(insertSql(common,amount)); return jdbc.queryForObject("SELECT MAX(ledger_id) FROM "+table(common),Long.class); }
    private static String insertSql(boolean common,long amount) { return "INSERT INTO "+table(common)+"(member_id,"+(common?"":"creator_id,")+"mission_completion_id,delta_amount,type,request_id) VALUES(1,"+(common?"":"1,")+"1,"+amount+",'EARN',UUID())"; }
    private static int state(boolean common,long id) { return jdbc.queryForObject("SELECT reward_notification_state FROM "+table(common)+" WHERE ledger_id=?",Integer.class,id); }
    private static int count(boolean common,long id) { return jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE "+(common?"common_ticket_ledger_id":"ticket_ledger_id")+"=?",Integer.class,id); }
    private static Notification find(boolean common,long id) { return (common?notifications.findByCommonTicketLedgerId(id):notifications.findByTicketLedgerId(id)).orElseThrow(); }
    private static String table(boolean common) { return common?"common_ticket_ledger":"ticket_ledger"; }
    private static Connection connection() throws SQLException {
        if(!MYSQL.isRunning()||MYSQL.getMappedPort(3306)==3306) throw new IllegalStateException("dedicated container required");
        return DriverManager.getConnection(url,"root","test");
    }

    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement
    static class Config {
        @Bean DataSource dataSource() { return new DriverManagerDataSource(url,"root","test"); }
        @Bean JdbcTemplate jdbcTemplate(DataSource ds) { return spy(new JdbcTemplate(ds)); }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
            var factory=new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(ds);
            factory.setManagedTypes(PersistenceManagedTypes.of(Notification.class.getName(),
                    "kr.co.cking.ticket.domain.TicketLedger","kr.co.cking.ticket.domain.CommonTicketLedger",
                    "kr.co.cking.event.domain.Event","kr.co.cking.event.domain.EventPrize"));
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","validate",
                    "hibernate.physical_naming_strategy","org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl"));
            return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory emf,DataSource ds) {
            var tm=new JpaTransactionManager(emf); tm.setDataSource(ds); return tm;
        }
        @Bean NotificationRepository notificationRepository(EntityManagerFactory emf) {
            return new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(emf))
                    .getRepository(NotificationRepository.class);
        }
        @Bean RewardNotificationLedgerStore ledgerStore(JdbcTemplate jdbc) { return new RewardNotificationLedgerStore(jdbc); }
        @Bean TicketRewardNotificationService service(RewardNotificationLedgerStore ledgers,NotificationRepository notifications) {
            return new TicketRewardNotificationService(ledgers,notifications);
        }
        @Bean kr.co.cking.member.repository.MemberRepository members() {
            var members=mock(kr.co.cking.member.repository.MemberRepository.class);
            when(members.existsById(1L)).thenReturn(true); return members;
        }
        @Bean kr.co.cking.notification.application.NotificationReadService read(kr.co.cking.member.repository.MemberRepository members,NotificationRepository notifications) {
            return new kr.co.cking.notification.application.NotificationReadService(members,notifications);
        }
        @Bean kr.co.cking.notification.application.NotificationHideService hide(kr.co.cking.member.repository.MemberRepository members,NotificationRepository notifications) {
            return new kr.co.cking.notification.application.NotificationHideService(members,notifications);
        }
    }
}
