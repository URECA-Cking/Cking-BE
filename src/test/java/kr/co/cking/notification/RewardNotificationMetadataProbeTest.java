package kr.co.cking.notification;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.*;

/** Design experiment only. JDBC below is a test driver, not a production worker or migration. */
@Testcontainers
class RewardNotificationMetadataProbeTest {
    @Container
    static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4.10")
            .withEnv("MYSQL_ROOT_PASSWORD", "test").withEnv("MYSQL_DATABASE", "metadata_probe")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci")
            .withExposedPorts(3306)
            .waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(3)));
    private static String url;

    @BeforeAll
    static void schema() throws Exception {
        assertThat(MYSQL.getMappedPort(3306)).isNotEqualTo(3306);
        url = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
                + "/metadata_probe?connectionTimeZone=UTC";
        Flyway.configure().dataSource(url, "root", "test").locations("classpath:db/migration")
                .target("88").load().migrate();
        try (Connection c = connection()) {
            sql(c, "INSERT INTO member(member_id,name,role) VALUES(1,'probe','USER')");
            sql(c, "INSERT INTO creator(creator_id,member_id,name,activity_name,activity_name_normalized) VALUES(1,1,'probe','probe','probe')");
            sql(c, "INSERT INTO mission(mission_id,creator_id,type,reward_amount) VALUES(1,1,'LIKE',1)");
            sql(c, "INSERT INTO mission_completion(completion_id,member_id,creator_id,mission_id,period_key,completion_key,request_id,payload_fingerprint,completed_at) "
                    + "VALUES(1,1,1,1,'2026-10-21','2026-10-21',UUID(),REPEAT('a',64),UTC_TIMESTAMP(6))");
            sql(c, "INSERT INTO common_mission_completion(completion_id,member_id,mission_id,period_key,request_id,payload_fingerprint,completed_at) "
                    + "SELECT 1,1,mission_id,'2026-10-21',UUID(),REPEAT('a',64),UTC_TIMESTAMP(6) FROM common_mission WHERE type='ATTENDANCE'");
            for (boolean common : new boolean[]{false, true}) {
                String table = ledger(common);
                sql(c, "ALTER TABLE " + table + " ADD COLUMN notice_state TINYINT NOT NULL DEFAULT 0,"
                        + " ADD COLUMN notice_retry_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),"
                        + " ADD COLUMN notice_id BIGINT NULL,"
                        + " ADD CONSTRAINT probe_" + table + "_state CHECK (notice_state IN (0,1,2) AND"
                        + " ((notice_state=2 AND notice_id IS NOT NULL) OR (notice_state<>2 AND notice_id IS NULL))),"
                        + " ADD UNIQUE KEY probe_" + table + "_owner(ledger_id,member_id),"
                        + " ADD INDEX probe_" + table + "_due(notice_state,type,notice_retry_at,ledger_id)");
            }
            sql(c, "ALTER TABLE notification ADD COLUMN ticket_ledger_id BIGINT NULL, ADD COLUMN common_ticket_ledger_id BIGINT NULL,"
                    + "ADD UNIQUE KEY probe_notice_creator(ticket_ledger_id), ADD UNIQUE KEY probe_notice_common(common_ticket_ledger_id),"
                    + "ADD UNIQUE KEY probe_notice_creator_ref(id,ticket_ledger_id,member_id),"
                    + "ADD UNIQUE KEY probe_notice_common_ref(id,common_ticket_ledger_id,member_id),"
                    + "ADD CONSTRAINT probe_notice_creator_fk FOREIGN KEY(ticket_ledger_id,member_id) REFERENCES ticket_ledger(ledger_id,member_id),"
                    + "ADD CONSTRAINT probe_notice_common_fk FOREIGN KEY(common_ticket_ledger_id,member_id) REFERENCES common_ticket_ledger(ledger_id,member_id),"
                    + "ADD CONSTRAINT probe_notice_source CHECK ("
                    + "(type='COMMON_TICKET_REWARDED' AND common_ticket_ledger_id IS NOT NULL AND ticket_ledger_id IS NULL AND event_id IS NULL AND drawing_id IS NULL AND winner_id IS NULL AND sanction_id IS NULL) OR "
                    + "(type='CREATOR_TICKET_REWARDED' AND ticket_ledger_id IS NOT NULL AND common_ticket_ledger_id IS NULL AND event_id IS NULL AND drawing_id IS NULL AND winner_id IS NULL AND sanction_id IS NULL) OR "
                    + "(type NOT IN ('COMMON_TICKET_REWARDED','CREATOR_TICKET_REWARDED') AND ticket_ledger_id IS NULL AND common_ticket_ledger_id IS NULL))");
            for (boolean common : new boolean[]{false, true}) {
                sql(c, "ALTER TABLE " + ledger(common) + " ADD CONSTRAINT probe_" + ledger(common) + "_receipt "
                        + "FOREIGN KEY(notice_id,ledger_id,member_id) REFERENCES notification(id," + reference(common) + ",member_id)");
            }
        }
        System.out.println("METADATA_PROBE mysql=" + MYSQL.getDockerImageName() + " dynamicPort=" + MYSQL.getMappedPort(3306));
    }

    @BeforeEach
    void resetOnlyProbeData() throws Exception {
        try (Connection c = connection()) {
            for (boolean common : new boolean[]{false, true}) {
                sql(c, "UPDATE " + ledger(common) + " SET notice_state=0,notice_id=NULL");
                sql(c, "ALTER TABLE " + ledger(common) + " ALTER COLUMN notice_state SET DEFAULT 0");
            }
            sql(c, "DELETE FROM notification");
            sql(c, "DELETE FROM ticket_ledger");
            sql(c, "DELETE FROM common_ticket_ledger");
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void oldRowsExcludedNewRowsPendingAndPaymentColumnsRemainUnchanged(boolean common) throws Exception {
        try (Connection c = connection()) {
            long old = earn(c, common); activate(c, common); long fresh = earn(c, common);
            assertThat(state(c, common, old)).isZero();
            assertThat(due(c, common, 10)).containsExactly(fresh);
            String before = paymentSnapshot(c, common, fresh);
            assertThat(process(common, fresh, 0)).isTrue();
            assertThat(state(c, common, fresh)).isEqualTo(2);
            assertThat(paymentSnapshot(c, common, fresh)).isEqualTo(before);
            assertThat(due(c, common, 10)).isEmpty();
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void committedNotificationAndDoneMetadataAreAtomic(boolean common) throws Exception {
        try (Connection observer = connection(); Connection writer = connection()) {
            activate(observer, common); long id = earn(observer, common);
            writer.setAutoCommit(false);
            long notice = insertNotice(writer, common, id);
            markDone(writer, common, id, notice);
            assertThat(state(observer, common, id)).isEqualTo(1);
            assertThat(notices(observer, common, id)).isZero();
            writer.commit();
            assertThat(state(observer, common, id)).isEqualTo(2);
            assertThat(notices(observer, common, id)).isEqualTo(1);
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void failuresAtBothWriteStagesRollbackAndFreshConnectionRecovers(boolean common) throws Exception {
        try (Connection c = connection()) {
            activate(c, common);
            for (int stage : new int[]{1,2}) {
                long id = earn(c, common);
                assertThatThrownBy(() -> process(common, id, stage)).isInstanceOf(SQLException.class);
                try (Connection restarted = connection()) {
                    assertThat(state(restarted, common, id)).isEqualTo(1);
                    assertThat(notices(restarted, common, id)).isZero();
                    assertThat(scalar(restarted, "SELECT COUNT(*) FROM " + ledger(common) + " WHERE ledger_id=" + id)).isEqualTo(1);
                }
                assertThat(process(common, id, 0)).isTrue();
                assertThat(notices(c, common, id)).isEqualTo(1);
            }
            long failedInsert = earn(c, common);
            try (Connection writer = connection()) {
                writer.setAutoCommit(false);
                try {
                    sql(writer, "INSERT INTO notification(member_id,type,title,body," + reference(common)
                            + ") VALUES(1,'" + type(common) + "',NULL,'probe'," + failedInsert + ")");
                    fail("NOT NULL violation required");
                } catch (SQLException expected) { writer.rollback(); }
            }
            assertThat(state(c, common, failedInsert)).isEqualTo(1);
            assertThat(notices(c, common, failedInsert)).isZero();
            assertThat(process(common, failedInsert, 0)).isTrue();
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void existingHiddenNotificationRepairsPendingMetadataWithoutRecreation(boolean common) throws Exception {
        try (Connection c = connection()) {
            activate(c, common); long id = earn(c, common);
            long notice = insertNotice(c, common, id);
            sql(c, "UPDATE notification SET hidden_at=UTC_TIMESTAMP(6) WHERE id=" + notice);
            assertThat(state(c, common, id)).isEqualTo(1);
            process(common, id, 0);
            assertThat(state(c, common, id)).isEqualTo(2);
            assertThat(notices(c, common, id)).isEqualTo(1);
            assertThat(scalar(c, "SELECT COUNT(*) FROM notification WHERE id=" + notice + " AND hidden_at IS NOT NULL")).isEqualTo(1);
            assertThat(process(common, id, 0)).isFalse();
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void missingOrWrongReceiptCannotBeMarkedDone(boolean common) throws Exception {
        try (Connection c = connection()) {
            activate(c, common); long first = earn(c, common), second = earn(c, common);
            long notice = insertNotice(c, common, first);
            assertError(() -> sql(c, "UPDATE " + ledger(common) + " SET notice_state=2 WHERE ledger_id=" + second), 3819);
            assertError(() -> markDone(c, common, second, 99999999L), 1452);
            assertError(() -> markDone(c, common, second, notice), 1452);
            markDone(c, common, first, notice);
            assertError(() -> sql(c, "DELETE FROM notification WHERE id=" + notice), 1451);
            assertThat(state(c, common, second)).isEqualTo(1);
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void concurrentTransactionsProduceOneNotificationAndDoneState(boolean common) throws Exception {
        long id;
        try (Connection c = connection()) { activate(c, common); id=earn(c, common); }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        Callable<Boolean> task = () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("start barrier");
            return process(common, id, 0);
        };
        try {
            Future<Boolean> a=pool.submit(task), b=pool.submit(task);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat((a.get(20, TimeUnit.SECONDS)?1:0)+(b.get(20, TimeUnit.SECONDS)?1:0)).isEqualTo(1);
            try (Connection c=connection()) {
                assertThat(notices(c, common, id)).isEqualTo(1);
                assertThat(state(c, common, id)).isEqualTo(2);
            }
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void lateLowerIdIsFoundAfterHigherIdIsAlreadyDone(boolean common) throws Exception {
        try (Connection c=connection(); Connection late=connection()) {
            activate(c, common); late.setAutoCommit(false);
            long low=earn(late, common), high=earn(c, common);
            assertThat(high).isGreaterThan(low);
            assertThat(due(c, common, 10)).containsExactly(high);
            process(common, high, 0);
            assertThat(due(c, common, 10)).isEmpty();
            late.commit();
            assertThat(due(c, common, 10)).containsExactly(low);
            process(common, low, 0);
            assertThat(due(c, common, 10)).isEmpty();
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void persistedCooldownLetsBatchOfOnePassRepeatedFailure(boolean common) throws Exception {
        try (Connection c=connection()) {
            activate(c, common); long bad=earn(c, common), good=earn(c, common);
            assertThat(due(c, common, 1)).containsExactly(bad);
            assertThatThrownBy(() -> process(common, bad, 1)).isInstanceOf(SQLException.class);
            defer(common, bad);
            // A worker that selected this ID before another worker deferred it must recheck
            // the persisted due time under the row lock, not bypass the cooldown.
            assertThat(process(common, bad, 0)).isFalse();
            try (Connection restarted=connection()) { assertThat(due(restarted, common, 1)).containsExactly(good); }
            process(common, good, 0);
            assertThat(due(c, common, 1)).isEmpty();
            // Advance this test record's due time, not the wall clock or the paid-at timestamp.
            sql(c, "UPDATE " + ledger(common) + " SET notice_retry_at='1970-01-01' WHERE ledger_id=" + bad);
            assertThat(due(c, common, 1)).containsExactly(bad);
            assertThatThrownBy(() -> process(common, bad, 1)).isInstanceOf(SQLException.class);
            defer(common, bad);
            long next=earn(c, common);
            assertThat(due(c, common, 1)).containsExactly(next);
            process(common, next, 0);
            sql(c, "UPDATE " + ledger(common) + " SET notice_retry_at='1970-01-01' WHERE ledger_id=" + bad);
            process(common, bad, 0);
            assertThat(state(c, common, bad)).isEqualTo(2);
            defer(common, bad); // late failure handler must not change a completed record
            assertThat(state(c, common, bad)).isEqualTo(2);
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void retryWhoseDueTimeHasPassedIsNotStarvedByNewArrivals(boolean common) throws Exception {
        try(Connection c=connection()) {
            activate(c,common); long retry=earn(c,common);
            defer(common,retry);
            sql(c,"UPDATE "+ledger(common)+" SET notice_retry_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE ledger_id="+retry);
            long fresh=earn(c,common);
            assertThat(due(c,common,1)).containsExactly(retry);
            process(common,retry,0);
            assertThat(due(c,common,1)).containsExactly(fresh);
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void explainUsesDueIndexAndDoesNotRescanCompletedHistory(boolean common) throws Exception {
        try (Connection c=connection()) {
            activate(c, common);
            String digits="(SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)";
            sql(c, "INSERT INTO " + ledger(common) + "(member_id," + (common?"":"creator_id,")
                    + "mission_completion_id,delta_amount,type,request_id) SELECT 1," + (common?"":"1,")
                    + "1,2,'EARN',UUID() FROM " + digits + " a CROSS JOIN " + digits + " b CROSS JOIN " + digits + " d");
            sql(c, "INSERT INTO notification(member_id,type,title,body," + reference(common) + ") SELECT 1,'" + type(common)
                    + "','probe','probe',ledger_id FROM " + ledger(common));
            sql(c, "UPDATE " + ledger(common) + " l JOIN notification n ON n." + reference(common)
                    + "=l.ledger_id SET l.notice_state=2,l.notice_id=n.id");
            for(int i=0;i<20;i++) earn(c, common);
            long failed=earn(c, common); defer(common, failed);
            sql(c, "ANALYZE TABLE " + ledger(common));
            String plan=text(c,"EXPLAIN FORMAT=TREE " + dueQuery(common,10));
            assertThat(plan).contains("probe_"+ledger(common)+"_due");
            System.out.println("METADATA_EXPLAIN " + ledger(common) + "\n" + plan);
            System.out.println("METADATA_ANALYZE " + ledger(common) + "\n" + text(c,"EXPLAIN ANALYZE " + dueQuery(common,10)));
            List<Long> ids=due(c, common,10);
            assertThat(ids).hasSize(10).doesNotContain(failed);
            for(long id:ids) assertThat(state(c, common,id)).isEqualTo(1);
            assertThat(scalar(c,"SELECT COUNT(*) FROM " + ledger(common) + " WHERE notice_state=2")).isEqualTo(1000);
        }
    }

    @Test
    void actualDedicatedMysqlRestartPreservesBoundaryDoneAndRetryMetadata() throws Exception {
        long[] pending=new long[2], done=new long[2];
        try(Connection c=connection()) {
            int i=0;
            for(boolean common:new boolean[]{false,true}) {
                long old=earn(c,common); activate(c,common);
                done[i]=earn(c,common); process(common,done[i],0);
                pending[i]=earn(c,common); defer(common,pending[i]);
                assertThat(state(c,common,old)).isZero(); i++;
            }
        }
        // Only this @Container's exact ID is restarted; no named/local DB operation.
        MYSQL.getDockerClient().restartContainerCmd(MYSQL.getContainerId()).withTimeout(10).exec();
        // Docker may reassign an automatically allocated host port on restart.
        // Inspect only this container rather than using GenericContainer's cached port metadata.
        var info=MYSQL.getDockerClient().inspectContainerCmd(MYSQL.getContainerId()).exec();
        int restartedPort=Integer.parseInt(info.getNetworkSettings().getPorts().getBindings()
                .get(com.github.dockerjava.api.model.ExposedPort.tcp(3306))[0].getHostPortSpec());
        assertThat(restartedPort).isNotEqualTo(3306);
        System.out.println("METADATA_RESTART endpoint before="+url+" afterPort="+restartedPort);
        url="jdbc:mysql://"+MYSQL.getHost()+":"+restartedPort+"/metadata_probe?connectionTimeZone=UTC";
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
        SQLException last=null;
        boolean connected=false;
        while(System.nanoTime()<deadline) {
            try(Connection c=connection()) { scalar(c,"SELECT 1"); connected=true; break; }
            catch(SQLException e) { last=e; LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100)); }
        }
        if(!connected) throw new AssertionError("dedicated MySQL restart did not complete\n"+MYSQL.getLogs(),last);
        try(Connection c=connection()) {
            int i=0;
            for(boolean common:new boolean[]{false,true}) {
                assertThat(state(c,common,done[i])).isEqualTo(2);
                assertThat(notices(c,common,done[i])).isEqualTo(1);
                assertThat(state(c,common,pending[i])).isEqualTo(1);
                assertThat(due(c,common,10)).isEmpty();
                long fresh=earn(c,common);
                assertThat(due(c,common,10)).containsExactly(fresh);
                sql(c,"UPDATE " + ledger(common) + " SET notice_retry_at='1970-01-01' WHERE ledger_id="+pending[i]);
                process(common,pending[i],0); i++;
            }
        }
        System.out.println("METADATA_RESTART boundary, notification receipts and cooldown survived actual container restart");
    }

    private static boolean process(boolean common,long id,int failStage) throws SQLException {
        try(Connection c=connection()) {
            c.setAutoCommit(false);
            try {
                long state=scalar(c,"SELECT notice_state FROM " + ledger(common) + " WHERE ledger_id="+id+" FOR UPDATE");
                if(state!=1) { c.commit(); return false; }
                if(scalar(c,"SELECT notice_retry_at<=UTC_TIMESTAMP(6) FROM "+ledger(common)+" WHERE ledger_id="+id)==0) {
                    c.commit(); return false;
                }
                long receipt;
                try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT id FROM notification WHERE "+reference(common)+"="+id)) {
                    receipt=r.next()?r.getLong(1):0;
                }
                if(receipt==0) receipt=insertNotice(c,common,id);
                if(failStage==1) throw new SQLException("probe failure after notification insert");
                markDone(c,common,id,receipt);
                if(failStage==2) throw new SQLException("probe failure after metadata update");
                c.commit(); return true;
            } catch(SQLException|RuntimeException e) { c.rollback(); throw e; }
        }
    }
    private static void defer(boolean common,long id) throws SQLException {
        try(Connection c=connection()) {
            sql(c,"UPDATE "+ledger(common)+" SET notice_retry_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 60 SECOND) WHERE ledger_id="+id+" AND notice_state=1");
        }
    }
    private static String dueQuery(boolean common,int limit) {
        return "SELECT ledger_id FROM "+ledger(common)+" WHERE notice_state=1 AND type='EARN' AND notice_retry_at<=UTC_TIMESTAMP(6) ORDER BY notice_retry_at,ledger_id LIMIT "+limit;
    }
    private static List<Long> due(Connection c,boolean common,int limit) throws SQLException {
        List<Long> result=new ArrayList<>();
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery(dueQuery(common,limit))) { while(r.next()) result.add(r.getLong(1)); }
        return result;
    }
    private static void activate(Connection c,boolean common) throws SQLException { sql(c,"ALTER TABLE "+ledger(common)+" ALTER COLUMN notice_state SET DEFAULT 1"); }
    private static long earn(Connection c,boolean common) throws SQLException {
        return insert(c,"INSERT INTO "+ledger(common)+"(member_id,"+(common?"":"creator_id,")+"mission_completion_id,delta_amount,type,request_id,balance_before,balance_after) VALUES(1,"+(common?"":"1,")+"1,2,'EARN',UUID(),0,2)");
    }
    private static long insertNotice(Connection c,boolean common,long ledger) throws SQLException {
        return insert(c,"INSERT INTO notification(member_id,type,title,body,"+reference(common)+") VALUES(1,'"+type(common)+"','probe','probe',"+ledger+")");
    }
    private static void markDone(Connection c,boolean common,long ledger,long notice) throws SQLException {
        sql(c,"UPDATE "+ledger(common)+" SET notice_state=2,notice_id="+notice+" WHERE ledger_id="+ledger);
    }
    private static long state(Connection c,boolean common,long id) throws SQLException { return scalar(c,"SELECT notice_state FROM "+ledger(common)+" WHERE ledger_id="+id); }
    private static long notices(Connection c,boolean common,long id) throws SQLException { return scalar(c,"SELECT COUNT(*) FROM notification WHERE "+reference(common)+"="+id); }
    private static String paymentSnapshot(Connection c,boolean common,long id) throws SQLException {
        return text(c,"SELECT CONCAT_WS('|',member_id,"+(common?"":"creator_id,")+"mission_completion_id,delta_amount,type,request_id,balance_before,balance_after,created_at) FROM "+ledger(common)+" WHERE ledger_id="+id);
    }
    private static String ledger(boolean common) { return common?"common_ticket_ledger":"ticket_ledger"; }
    private static String reference(boolean common) { return common?"common_ticket_ledger_id":"ticket_ledger_id"; }
    private static String type(boolean common) { return common?"COMMON_TICKET_REWARDED":"CREATOR_TICKET_REWARDED"; }
    private static Connection connection() throws SQLException {
        if(!MYSQL.isRunning()||MYSQL.getMappedPort(3306)==3306) throw new IllegalStateException("isolated container required");
        return DriverManager.getConnection(url,"root","test");
    }
    private static void sql(Connection c,String q) throws SQLException { try(Statement s=c.createStatement()) { s.execute(q); } }
    private static long insert(Connection c,String q) throws SQLException {
        try(Statement s=c.createStatement()) { s.executeUpdate(q,Statement.RETURN_GENERATED_KEYS); try(ResultSet r=s.getGeneratedKeys()) { assertThat(r.next()).isTrue(); return r.getLong(1); } }
    }
    private static long scalar(Connection c,String q) throws SQLException {
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery(q)) { assertThat(r.next()).isTrue(); return r.getLong(1); }
    }
    private static String text(Connection c,String q) throws SQLException {
        StringBuilder out=new StringBuilder();
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery(q)) { while(r.next()) out.append(r.getString(1)).append('\n'); }
        return out.toString();
    }
    @FunctionalInterface private interface SqlAction { void run() throws SQLException; }
    private static void assertError(SqlAction action,int code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
