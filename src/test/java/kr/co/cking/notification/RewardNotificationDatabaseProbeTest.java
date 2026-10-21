package kr.co.cking.notification;

import kr.co.cking.ticket.domain.CommonTicketLedger;
import kr.co.cking.ticket.domain.TicketLedger;
import kr.co.cking.ticket.domain.TicketLedgerType;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import probe.notification.RewardNotificationMarkerProbes.CommonMarkerProbe;
import probe.notification.RewardNotificationMarkerProbes.CreatorMarkerProbe;

import java.sql.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.*;

/** Experimental DDL only: not a production migration or a notification implementation.
 * No Spring context, Redis, provider, or local-datasource fallback. */
@Testcontainers
class RewardNotificationDatabaseProbeTest {
    @Container
    static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4.10")
            .withEnv("MYSQL_ROOT_PASSWORD", "test")
            .withEnv("MYSQL_DATABASE", "probe_bootstrap")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci")
            .withExposedPorts(3306)
            .waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    private String database;
    private String url;

    @BeforeEach
    void isolatedSchema() throws Exception {
        assertThat(MYSQL.isRunning()).isTrue();
        assertThat(MYSQL.getMappedPort(3306)).isNotEqualTo(3306);
        database = "reward_probe_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = DriverManager.getConnection(containerUrl("probe_bootstrap"), "root", "test")) {
            execute(c, "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        url = containerUrl(database);
        Flyway.configure().dataSource(url, "root", "test").locations("classpath:db/migration")
                .target("88").load().migrate();
        try (Connection c = connection()) {
            execute(c, "INSERT INTO member(member_id,name,role) VALUES(1,'probe','USER'),(2,'other','USER')");
            execute(c, "INSERT INTO creator(creator_id,member_id,name,activity_name,activity_name_normalized) VALUES(1,1,'probe','probe','probe')");
            execute(c, "INSERT INTO mission(mission_id,creator_id,type,reward_amount) VALUES(1,1,'LIKE',1)");
            execute(c, "INSERT INTO mission_completion(completion_id,member_id,creator_id,mission_id,period_key,completion_key,request_id,payload_fingerprint,completed_at) "
                    + "VALUES(1,1,1,1,'2026-10-21','2026-10-21',UUID(),REPEAT('a',64),UTC_TIMESTAMP(6))");
            execute(c, "INSERT INTO common_mission_completion(completion_id,member_id,mission_id,period_key,request_id,payload_fingerprint,completed_at) "
                    + "SELECT 1,1,mission_id,'2026-10-21',UUID(),REPEAT('a',64),UTC_TIMESTAMP(6) FROM common_mission WHERE type='ATTENDANCE'");
        }
        System.out.println("PROBE mysql=" + MYSQL.getDockerImageName() + " endpoint=" + MYSQL.getHost()
                + ":" + MYSQL.getMappedPort(3306) + " database=" + database);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void transactionThatInsertedBeforeColumnAdditionBlocksDdl(boolean common) throws Exception {
        String table = ledger(common);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection admin = connection(); Connection writer = connection(); Connection ddl = connection()) {
            execute(ddl, "SET SESSION lock_wait_timeout=20");
            long ddlId = scalar(ddl, "SELECT CONNECTION_ID()");
            writer.setAutoCommit(false);
            long held = insertEarn(writer, common);
            Future<?> addition = executor.submit(() -> {
                try { addMarker(ddl, table); }
                catch (SQLException e) { throw new CompletionException(e); }
            });
            try { awaitMetadataWait(admin, ddlId, addition); }
            finally { writer.commit(); }
            addition.get(20, TimeUnit.SECONDS);
            activate(admin, table);
            assertThat(marker(admin, table, held)).isZero();
            assertThat(marker(admin, table, insertEarn(admin, common))).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }

    @Test
    void failedFlywayIsNotAutomaticallyRerunnableAndExplicitRecoveryPreservesBoundary(@TempDir Path scripts) throws Exception {
        Path script = scripts.resolve("V1__experimental_marker.sql");
        Files.writeString(script, "ALTER TABLE ticket_ledger ADD COLUMN reward_notification_eligible BOOLEAN NOT NULL DEFAULT FALSE, ALGORITHM=INSTANT;\n"
                + "SELECT * FROM deliberately_missing_table;\n");
        Flyway probe = Flyway.configure().dataSource(url, "root", "test").locations("filesystem:" + scripts)
                .table("experimental_flyway_history").baselineOnMigrate(true).baselineVersion("0").load();
        assertThatThrownBy(probe::migrate).isInstanceOf(org.flywaydb.core.api.FlywayException.class);
        try (Connection c = connection()) { insertEarn(c, false); }
        // MySQL committed ADD COLUMN despite the later failure. Plain rerun is NOT safe.
        assertThatThrownBy(probe::migrate).isInstanceOf(org.flywaydb.core.api.FlywayException.class);
        // Controlled, TEST-ONLY operator recovery. No production migration or automatic repair.
        Files.writeString(script, "ALTER TABLE ticket_ledger ALTER COLUMN reward_notification_eligible SET DEFAULT TRUE, ALGORITHM=INSTANT;\n");
        probe.repair();
        probe.migrate();
        try (Connection c = connection()) {
            assertThat(marker(c, "ticket_ledger", 1)).isZero();
            assertThat(marker(c, "ticket_ledger", insertEarn(c, false))).isEqualTo(1);
        }
        System.out.println("FLYWAY_FAILURE: partial DDL survives; plain rerun fails; explicit test-only recovery preserves flags");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void historicalRowsAndBothSidesOfDefaultBoundary(boolean common) throws Exception {
        String table = ledger(common);
        try (Connection c = connection()) {
            long old = insertEarn(c, common);
            addMarker(c, table);
            long during = insertEarn(c, common);
            activate(c, table);
            long fresh = insertEarn(c, common);
            assertThat(marker(c, table, old)).isZero();
            assertThat(marker(c, table, during)).isZero();
            assertThat(marker(c, table, fresh)).isEqualTo(1);
            // New JDBC session represents process restart: no in-memory cutoff.
            try (Connection restarted = connection()) {
                assertThat(marker(restarted, table, old)).isZero();
                assertThat(marker(restarted, table, fresh)).isEqualTo(1);
                assertThat(scalar(restarted, "SELECT COUNT(*) FROM " + table + " WHERE reward_notification_eligible=1 AND type='EARN'")).isEqualTo(1);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void longTransactionBlocksActivationAndCommittedRowsHaveDeterministicBoundary(boolean common) throws Exception {
        String table = ledger(common);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection admin = connection(); Connection writer = connection(); Connection ddl = connection()) {
            addMarker(admin, table);
            execute(ddl, "SET SESSION lock_wait_timeout=20");
            long ddlId = scalar(ddl, "SELECT CONNECTION_ID()");
            writer.setAutoCommit(false);
            long held = insertEarn(writer, common);
            // Observe visibility before the DDL is queued; a later table SELECT could queue
            // behind the exclusive metadata lock and prevent this test from committing writer.
            assertThat(scalar(admin, "SELECT COUNT(*) FROM " + table)).isZero();
            Future<?> activation = executor.submit(() -> {
                try { activate(ddl, table); }
                catch (SQLException e) { throw new CompletionException(e); }
            });
            try {
                awaitMetadataWait(admin, ddlId, activation);
                assertThat(activation.isDone()).isFalse();
            } finally {
                writer.commit();
            }
            activation.get(20, TimeUnit.SECONDS);
            assertThat(marker(admin, table, held)).isZero();
            long after = insertEarn(admin, common);
            assertThat(marker(admin, table, after)).isEqualTo(1);
            System.out.println("BOUNDARY " + table + ": waiting DDL observed; pre-switch commit=0, post-switch insert=1");
        } finally { executor.shutdownNow(); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lateCommitIsInvisibleUntilCommitAndFoundWithoutPermanentHighWatermark(boolean common) throws Exception {
        String table = ledger(common);
        try (Connection read = connection(); Connection held = connection()) {
            addMarker(read, table); activate(read, table);
            held.setAutoCommit(false);
            long late = insertEarn(held, common);
            long higher = insertEarn(read, common);
            assertThat(higher).isGreaterThan(late);
            assertThat(scalar(read, "SELECT COUNT(*) FROM " + table + " WHERE reward_notification_eligible=1")).isEqualTo(1);
            held.commit();
            assertThat(scalar(read, "SELECT COUNT(*) FROM " + table + " WHERE reward_notification_eligible=1")).isEqualTo(2);
            assertThat(scalar(read, "SELECT COUNT(*) FROM " + table + " WHERE reward_notification_eligible=1 AND ledger_id>" + higher)).isZero();
            System.out.println("LATE_COMMIT " + table + ": full pending rescan sees 2; permanent ID cursor would omit lower ID");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void partialDdlFailureAndMetadataAwareResumeDoNotReclassifyRows(boolean common) throws Exception {
        String table = ledger(common);
        try (Connection c = connection()) {
            long old = insertEarn(c, common);
            addMarker(c, table);
            long between = insertEarn(c, common);
            assertThatThrownBy(() -> execute(c, "ALTER TABLE " + table + " ADD COLUMN intentionally_broken NONEXISTENT_TYPE"))
                    .isInstanceOf(SQLException.class);
        }
        try (Connection resumed = connection()) {
            // Explicit recovery experiment, not an automatic Flyway repair policy.
            assertThat(scalar(resumed, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='"
                    + table + "' AND column_name='reward_notification_eligible'")).isEqualTo(1);
            activate(resumed, table);
            long newRow = insertEarn(resumed, common);
            activate(resumed, table); // resume after failure following activation must not reset old values
            assertThat(scalar(resumed, "SELECT COUNT(*) FROM " + table + " WHERE reward_notification_eligible=0")).isEqualTo(2);
            assertThat(marker(resumed, table, newRow)).isEqualTo(1);
            assertThatThrownBy(() -> addMarker(resumed, table)).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void realLedgerHibernateInsertsUseDatabaseDefaultsAndReadOnlyMappingValidates() throws Exception {
        try (Connection c = connection()) {
            addMarker(c, "ticket_ledger"); activate(c, "ticket_ledger");
            addMarker(c, "common_ticket_ledger"); activate(c, "common_ticket_ledger");
        }
        // Actual production entities, no Spring datasource or application startup.
        try (SessionFactory sf = configuration().addAnnotatedClass(TicketLedger.class)
                .addAnnotatedClass(CommonTicketLedger.class).buildSessionFactory()) {
            sf.inTransaction(session -> {
                session.persist(TicketLedger.builder().memberId(1L).creatorId(1L).missionCompletionId(1L)
                        .deltaAmount(3L).type(TicketLedgerType.EARN).requestId(UUID.randomUUID().toString())
                        .createdAt(Instant.now()).build());
                session.persist(CommonTicketLedger.builder().memberId(1L).missionCompletionId(1L)
                        .deltaAmount(2L).type(TicketLedgerType.EARN).requestId(UUID.randomUUID().toString())
                        .createdAt(Instant.now()).build());
            });
        }
        try (Connection c = connection()) {
            assertThat(scalar(c, "SELECT reward_notification_eligible FROM ticket_ledger")).isEqualTo(1);
            assertThat(scalar(c, "SELECT reward_notification_eligible FROM common_ticket_ledger")).isEqualTo(1);
        }
        // Proposed marker-only mappings are TEST entities, not changes to production mappings.
        try (SessionFactory sf = configuration().addAnnotatedClass(CreatorMarkerProbe.class)
                .addAnnotatedClass(CommonMarkerProbe.class).buildSessionFactory()) {
            sf.inSession(s -> {
                assertThat(s.find(CreatorMarkerProbe.class, 1L).eligible).isTrue();
                assertThat(s.find(CommonMarkerProbe.class, 1L).eligible).isTrue();
            });
        }
    }

    @Test
    void uniqueForeignKeysChecksConcurrencyRollbackAndHiddenDeduplication() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection c = connection()) {
            addExperimentalNotificationConstraints(c);
            long creator = insertEarn(c, false), common = insertEarn(c, true);
            assertThat(creator).isEqualTo(common); // separate namespaces
            CountDownLatch start = new CountDownLatch(1);
            Callable<Boolean> insert = () -> {
                try (Connection candidate = connection()) {
                    start.await(10, TimeUnit.SECONDS);
                    try { insertNotification(candidate, creator, null, "CREATOR_TICKET_REWARDED", 1); return true; }
                    catch (SQLException e) { if (e.getErrorCode() == 1062) return false; throw e; }
                }
            };
            Future<Boolean> a = pool.submit(insert), b = pool.submit(insert);
            start.countDown();
            assertThat((a.get(20, TimeUnit.SECONDS) ? 1 : 0) + (b.get(20, TimeUnit.SECONDS) ? 1 : 0)).isEqualTo(1);
            insertNotification(c, null, common, "COMMON_TICKET_REWARDED", 1);
            assertThat(scalar(c, "SELECT COUNT(*) FROM notification")).isEqualTo(2);
            execute(c, "UPDATE notification SET hidden_at=UTC_TIMESTAMP(6) WHERE ticket_ledger_id=" + creator);
            assertSqlError(() -> insertNotification(c, creator, null, "CREATOR_TICKET_REWARDED", 1), 1062);
            assertSqlError(() -> insertNotification(c, 999L, null, "CREATOR_TICKET_REWARDED", 1), 1452);
            assertSqlError(() -> insertNotification(c, creator, common, "COMMON_TICKET_REWARDED", 1), 3819);
            assertSqlError(() -> insertNotification(c, null, null, "COMMON_TICKET_REWARDED", 1), 3819);
            assertSqlError(() -> insertNotification(c, creator, null, "COMMON_TICKET_REWARDED", 1), 3819);
            assertSqlError(() -> insertNotification(c, null, null, "CREATOR_TICKET_REWARDED", 1), 3819);
            // Composite source FK also rejects another member referencing this ledger.
            long otherCommon = insertEarn(c, true);
            assertSqlError(() -> insertNotification(c, null, otherCommon, "COMMON_TICKET_REWARDED", 2), 1452);
            long recoverable = insertEarn(c, false);
            try (Connection failed = connection()) {
                failed.setAutoCommit(false);
                insertNotification(failed, recoverable, null, "CREATOR_TICKET_REWARDED", 1);
                failed.rollback();
            }
            assertThat(scalar(c, "SELECT COUNT(*) FROM ticket_ledger WHERE ledger_id=" + recoverable)).isEqualTo(1);
            assertThat(scalar(c, "SELECT COUNT(*) FROM notification WHERE ticket_ledger_id=" + recoverable)).isZero();
            insertNotification(c, recoverable, null, "CREATOR_TICKET_REWARDED", 1);
            assertThat(scalar(c, "SELECT COUNT(*) FROM notification WHERE ticket_ledger_id=" + recoverable)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explainPendingQueryUsesIndexButScanningCostGrowsWithNotifiedHistory(boolean common) throws Exception {
        String table = ledger(common), reference = common ? "common_ticket_ledger_id" : "ticket_ledger_id";
        try (Connection c = connection()) {
            addMarker(c, table); activate(c, table); addExperimentalNotificationConstraints(c);
            String digits = "(SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)";
            execute(c, "INSERT INTO " + table + "(member_id," + (common ? "" : "creator_id,")
                    + "mission_completion_id,delta_amount,type,request_id) SELECT 1," + (common ? "" : "1,")
                    + "1,1,'EARN',UUID() FROM " + digits + " a CROSS JOIN " + digits + " b CROSS JOIN " + digits + " d");
            execute(c, "UPDATE " + table + " SET reward_notification_eligible=0 WHERE ledger_id<=900");
            execute(c, "CREATE INDEX probe_pending_index ON " + table + "(reward_notification_eligible,type,ledger_id)");
            String query = "SELECT l.ledger_id FROM " + table + " l WHERE l.reward_notification_eligible=1 AND l.type='EARN' AND l.delta_amount>0 "
                    + "AND NOT EXISTS(SELECT 1 FROM notification n WHERE n." + reference + "=l.ledger_id) ORDER BY l.ledger_id LIMIT 10";
            String plan = text(c, "EXPLAIN FORMAT=TREE " + query);
            System.out.println("EXPLAIN_BEFORE " + table + "\n" + plan);
            assertThat(plan).contains("probe_pending_index");
            assertThat(scalar(c, "SELECT COUNT(*) FROM (" + query + ") pending")).isEqualTo(10);
            execute(c, "INSERT INTO notification(member_id,type,title,body," + reference + ") SELECT 1,'"
                    + (common ? "COMMON_TICKET_REWARDED" : "CREATOR_TICKET_REWARDED") + "','probe','probe',ledger_id FROM " + table + " WHERE ledger_id BETWEEN 901 AND 990");
            System.out.println("EXPLAIN_ANALYZE " + table + "\n" + text(c, "EXPLAIN ANALYZE " + query));
            assertThat(scalar(c, "SELECT COUNT(*) FROM (" + query + ") pending")).isEqualTo(10);
            // Pending scan must count hidden notifications as already delivered.
            execute(c, "UPDATE notification SET hidden_at=UTC_TIMESTAMP(6)");
            assertThat(scalar(c, "SELECT MIN(ledger_id) FROM (" + query + ") pending")).isEqualTo(991);
        }
    }

    private static String ledger(boolean common) { return common ? "common_ticket_ledger" : "ticket_ledger"; }
    private static String containerUrl(String db) {
        if (!MYSQL.isRunning() || MYSQL.getMappedPort(3306) == 3306) throw new IllegalStateException("isolated MySQL required");
        return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + db + "?connectionTimeZone=UTC";
    }
    private Connection connection() throws SQLException { return DriverManager.getConnection(url, "root", "test"); }
    private Configuration configuration() {
        return new Configuration().setProperty("hibernate.connection.url", url)
                .setProperty("hibernate.connection.username", "root").setProperty("hibernate.connection.password", "test")
                .setProperty("hibernate.hbm2ddl.auto", "validate").setProperty("hibernate.show_sql", "true");
    }
    private static void addMarker(Connection c, String table) throws SQLException {
        execute(c, "ALTER TABLE " + table + " ADD COLUMN reward_notification_eligible BOOLEAN NOT NULL DEFAULT FALSE, ALGORITHM=INSTANT");
    }
    private static void activate(Connection c, String table) throws SQLException {
        execute(c, "ALTER TABLE " + table + " ALTER COLUMN reward_notification_eligible SET DEFAULT TRUE, ALGORITHM=INSTANT");
    }
    private static long insertEarn(Connection c, boolean common) throws SQLException {
        String sql = "INSERT INTO " + ledger(common) + "(member_id," + (common ? "" : "creator_id,")
                + "mission_completion_id,delta_amount,type,request_id) VALUES(1," + (common ? "" : "1,") + "1,2,'EARN',UUID())";
        try (Statement s = c.createStatement()) {
            s.executeUpdate(sql, Statement.RETURN_GENERATED_KEYS);
            try (ResultSet r = s.getGeneratedKeys()) { assertThat(r.next()).isTrue(); return r.getLong(1); }
        }
    }
    private static long marker(Connection c, String table, long id) throws SQLException {
        return scalar(c, "SELECT reward_notification_eligible FROM " + table + " WHERE ledger_id=" + id);
    }
    private static void execute(Connection c, String sql) throws SQLException { try (Statement s = c.createStatement()) { s.execute(sql); } }
    private static long scalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { assertThat(r.next()).isTrue(); return r.getLong(1); }
    }
    private static String text(Connection c, String sql) throws SQLException {
        StringBuilder out = new StringBuilder();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            while (r.next()) out.append(r.getString(1)).append('\n');
        }
        return out.toString();
    }
    private static void awaitMetadataWait(Connection observer, long id, Future<?> ddl) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (scalar(observer, "SELECT COUNT(*) FROM information_schema.processlist WHERE ID=" + id + " AND STATE='Waiting for table metadata lock'") == 1) return;
            if (ddl.isDone()) { ddl.get(); throw new AssertionError("DDL did not wait for the open transaction"); }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError("metadata-lock wait not observed within deadline");
    }
    private static void addExperimentalNotificationConstraints(Connection c) throws SQLException {
        execute(c, "ALTER TABLE ticket_ledger ADD UNIQUE KEY probe_creator_owner(ledger_id,member_id)");
        execute(c, "ALTER TABLE common_ticket_ledger ADD UNIQUE KEY probe_common_owner(ledger_id,member_id)");
        execute(c, "ALTER TABLE notification ADD COLUMN ticket_ledger_id BIGINT NULL, ADD COLUMN common_ticket_ledger_id BIGINT NULL,"
                + "ADD UNIQUE KEY probe_creator_once(ticket_ledger_id), ADD UNIQUE KEY probe_common_once(common_ticket_ledger_id),"
                + "ADD CONSTRAINT probe_creator_fk FOREIGN KEY(ticket_ledger_id,member_id) REFERENCES ticket_ledger(ledger_id,member_id),"
                + "ADD CONSTRAINT probe_common_fk FOREIGN KEY(common_ticket_ledger_id,member_id) REFERENCES common_ticket_ledger(ledger_id,member_id),"
                + "ADD CONSTRAINT probe_reward_source CHECK ((type='COMMON_TICKET_REWARDED' AND common_ticket_ledger_id IS NOT NULL AND ticket_ledger_id IS NULL AND event_id IS NULL AND drawing_id IS NULL AND winner_id IS NULL AND sanction_id IS NULL)"
                + " OR (type='CREATOR_TICKET_REWARDED' AND ticket_ledger_id IS NOT NULL AND common_ticket_ledger_id IS NULL AND event_id IS NULL AND drawing_id IS NULL AND winner_id IS NULL AND sanction_id IS NULL)"
                + " OR (type NOT IN ('COMMON_TICKET_REWARDED','CREATOR_TICKET_REWARDED') AND ticket_ledger_id IS NULL AND common_ticket_ledger_id IS NULL))");
    }
    private static void insertNotification(Connection c, Long creator, Long common, String type, long member) throws SQLException {
        execute(c, "INSERT INTO notification(member_id,type,title,body,ticket_ledger_id,common_ticket_ledger_id) VALUES("
                + member + ",'" + type + "','probe','probe'," + creator + "," + common + ")");
    }
    @FunctionalInterface private interface SqlAction { void run() throws SQLException; }
    private static void assertSqlError(SqlAction action, int code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
