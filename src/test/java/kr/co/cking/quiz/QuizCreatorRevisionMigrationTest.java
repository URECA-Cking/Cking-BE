package kr.co.cking.quiz;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V73(이슈 #581)을 기존 데이터가 있는 DB에 적용하는 이관 검증. 공용 테스트 DB와 분리된 MySQL 컨테이너에서 V72까지 적용하고
 * 이전 형태의 데이터를 넣은 뒤 V73을 적용한다. Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
class QuizCreatorRevisionMigrationTest {

    private static final String ROOT_PASSWORD = "test";

    @Container
    private static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4.10")
            .withEnv("MYSQL_ROOT_PASSWORD", ROOT_PASSWORD)
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+00:00")
            .withExposedPorts(3306)
            .waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    @Test
    void 기존_데이터는_v1과_LEGACY_승인으로_이관하고_게시_사실이_확인되는_revision만_고정한다() throws SQLException {
        String database = "quiz_migration_legacy";
        migrateToV72(database);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            // 모든 FK를 지키는 실제 데이터다. Judge 결과의 job·series 참조만 비활성화해 넣는다(V73은 그 테이블을 바꾸지 않는다).
            statement.execute("INSERT INTO member (member_id, name, role) VALUES (7, 'creator', 'USER'), (8, 'fan', 'USER')");
            statement.execute("INSERT INTO creator (creator_id, member_id, name, activity_name, activity_name_normalized) "
                    + "VALUES (1, 7, 'creator', 'creator', 'creator')");
            // q1 게시 중(r11 승인), q2 게시 취소·revision 하나·응시 있음, q3 게시 취소·revision 하나·응시 없음,
            // q4 게시 취소·revision 둘·응시 있음, q5 미게시(r51 거절),
            // q6 미게시인데 published_at 있음(V55 허용)·revision 하나·응시 있음
            quiz(statement, 1, "NOT_PUBLISHED");
            quiz(statement, 2, "UNPUBLISHED");
            quiz(statement, 3, "UNPUBLISHED");
            quiz(statement, 4, "UNPUBLISHED");
            quiz(statement, 5, "NOT_PUBLISHED");
            quiz(statement, 6, "NOT_PUBLISHED");
            revision(statement, 11, 1, 1);
            revision(statement, 21, 2, 1);
            revision(statement, 31, 3, 1);
            revision(statement, 41, 4, 1);
            revision(statement, 42, 4, 2);
            revision(statement, 51, 5, 1);
            revision(statement, 61, 6, 1);
            statement.execute("UPDATE quiz SET publication_status = 'PUBLISHED', published_revision_id = 11, "
                    + "published_at = UTC_TIMESTAMP(6) WHERE quiz_id = 1");
            statement.execute("UPDATE quiz SET published_at = UTC_TIMESTAMP(6) WHERE quiz_id = 6");
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            for (int revisionId : new int[]{11, 21, 31, 42}) {
                statement.execute("INSERT INTO quiz_judge_result (result_id, job_id, series_id, revision_id, input_sha256, "
                        + "settings_fingerprint, policy_version, aggregation, question_count, result_sha256, completed_at) "
                        + "VALUES (" + (9000 + revisionId) + ", " + revisionId + ", " + revisionId + ", " + revisionId
                        + ", REPEAT('i', 64), REPEAT('s', 64), 'p', 'ALL_PASS', 1, REPEAT('j', 64), UTC_TIMESTAMP(6))");
            }
            statement.execute("SET FOREIGN_KEY_CHECKS = 1");
            for (int revisionId : new int[]{11, 21, 31, 42}) {
                statement.execute("INSERT INTO quiz_revision_decision (revision_id, decision, decided_by_member_id, "
                        + "judge_evaluation_id, judge_evaluation_sha256, decided_at) VALUES (" + revisionId
                        + ", 'APPROVED', 7, " + (9000 + revisionId) + ", REPEAT('j', 64), UTC_TIMESTAMP(6))");
            }
            statement.execute("INSERT INTO quiz_revision_decision (revision_id, decision, decided_by_member_id, "
                    + "decided_at) VALUES (51, 'REJECTED', 7, UTC_TIMESTAMP(6))");
            attempt(statement, 2, 21);
            attempt(statement, 4, 42);
            attempt(statement, 6, 61);
        }
        Map<Long, String> hashesBefore = revisionHashes(database);

        migrate(database, null);

        assertThat(revisionHashes(database)).isEqualTo(hashesBefore);
        assertThat(query(database, "SELECT COUNT(*) FROM quiz_revision WHERE origin <> 'AI_GENERATED' "
                + "OR hash_version <> 'quiz-revision-v1' OR parent_revision_id IS NOT NULL")).isEqualTo("0");
        assertThat(query(database, "SELECT COUNT(*) FROM quiz_revision_decision d JOIN quiz_revision r "
                + "ON d.revision_id = r.revision_id WHERE d.decision = 'APPROVED' AND d.approval_policy = 'LEGACY_JUDGE_V1' "
                + "AND d.approved_revision_sha256 = r.revision_sha256 AND d.responsibility_confirmed IS NULL")).isEqualTo("4");
        assertThat(query(database, "SELECT CONCAT_WS(',', approval_policy, approved_revision_sha256) "
                + "FROM quiz_revision_decision WHERE revision_id = 51")).isEmpty();
        assertThat(lock(database, 1)).isEqualTo("1:11:" + hashesBefore.get(11L));
        assertThat(lock(database, 2)).isEqualTo("1:21:" + hashesBefore.get(21L));
        assertThat(lock(database, 3)).as("응시가 없어 게시 사실을 확인할 수 없음").isEqualTo("1:-:-");
        assertThat(lock(database, 4)).as("revision이 둘이라 먼저 게시된 revision을 알 수 없음").isEqualTo("1:-:-");
        assertThat(lock(database, 5)).as("published_at이 없는 미게시는 잠그지 않음").isEqualTo("0:-:-");
        assertThat(lock(database, 6)).as("published_at이 있으면 잠그지만 게시 revision은 확인할 수 없음")
                .isEqualTo("1:-:-");
        assertThat(query(database, "SELECT GROUP_CONCAT(publication_status ORDER BY quiz_id) FROM quiz"))
                .as("상태는 바꾸지 않음")
                .isEqualTo("PUBLISHED,UNPUBLISHED,UNPUBLISHED,UNPUBLISHED,NOT_PUBLISHED,NOT_PUBLISHED");
        assertThat(query(database, "SELECT CONCAT(publication_status, ':', IFNULL(published_revision_id, '-')) "
                + "FROM quiz WHERE quiz_id = 1")).isEqualTo("PUBLISHED:11");
        assertThat(query(database, "SELECT success FROM flyway_schema_history WHERE version = '73'")).isEqualTo("1");
    }

    /**
     * MySQL DDL은 문장마다 커밋되므로 V73이 중간에 실패하면 앞 문장의 변경이 남는다(자동 rollback 없음). 실패는 Flyway 이력에
     * 남고 이후 migrate는 거부되므로, 남은 변경을 수동으로 정리하고 flyway repair를 해야 한다.
     */
    @Test
    void V73이_중간에_실패하면_앞_문장의_변경이_남고_Flyway가_이후_적용을_거부한다() throws SQLException {
        String database = "quiz_migration_failure";
        migrateToV72(database);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE quiz_revision ADD INDEX uk_quiz_revision_hash (revision_id)");
        }

        assertThatThrownBy(() -> migrate(database, null)).isInstanceOf(FlywayException.class);

        assertThat(query(database, "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'quiz_revision' AND COLUMN_NAME = 'origin'")).as("첫 ALTER는 커밋돼 남는다").isEqualTo("1");
        assertThat(query(database, "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'quiz' AND COLUMN_NAME = 'content_locked'")).as("실패 이후 문장은 적용되지 않는다")
                .isEqualTo("0");
        assertThat(query(database, "SELECT success FROM flyway_schema_history WHERE version = '73'")).isEqualTo("0");
        assertThatThrownBy(() -> migrate(database, null)).isInstanceOf(FlywayException.class);
    }

    /**
     * V72까지 적용하고 V72가 실제로 성공 적용됐는지 확인한다. V72가 없는 브랜치에서는 target이 V71에서 멈출 수 있으므로 여기서
     * 실패시킨다. V72를 포함한 develop 위에서만 통과한다.
     */
    private static void migrateToV72(String database) throws SQLException {
        migrate(database, "72");
        assertThat(query(database, "SELECT CONCAT(COUNT(*), ':', IFNULL(MAX(version + 0), '-')) "
                + "FROM flyway_schema_history WHERE success = 1 AND version IN ('72', '73')"))
                .as("V72만 성공 적용되고 V73은 아직 적용되지 않음").isEqualTo("1:72");
    }

    private static void migrate(String database, String target) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url(""), "root", ROOT_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS " + database);
        }
        var configuration = Flyway.configure().dataSource(url(database), "root", ROOT_PASSWORD)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private static String url(String database) {
        return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + database
                + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&useSSL=false";
    }

    private static Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(url(database), "root", ROOT_PASSWORD);
    }

    /** 게시 중 상태는 revision을 넣은 뒤 UPDATE로 만든다(Quiz와 revision이 서로 참조한다). */
    private static void quiz(Statement statement, int quizId, String status) throws SQLException {
        String unpublished = "UNPUBLISHED".equals(status) ? "UTC_TIMESTAMP(6)" : "NULL";
        statement.execute("INSERT INTO quiz (quiz_id, creator_id, source_video_id, content_text, content_text_sha256, "
                + "publication_status, published_revision_id, published_at, unpublished_at, created_at, updated_at) VALUES ("
                + quizId + ", 1, 'dQw4w9WgXcQ', '원문', SHA2('원문', 256), '" + status + "', NULL, " + unpublished + ", "
                + unpublished + ", UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
    }

    private static void revision(Statement statement, int revisionId, int quizId, int revisionNo) throws SQLException {
        statement.execute("INSERT INTO quiz_revision (revision_id, quiz_id, revision_no, title, prompt_version, "
                + "question_count, option_count, revision_sha256, created_at) VALUES (" + revisionId + ", " + quizId + ", "
                + revisionNo + ", '제목', 'quiz-mcq-v1', 1, 4, SHA2('revision-" + revisionId + "', 256), UTC_TIMESTAMP(6))");
    }

    private static void attempt(Statement statement, int quizId, int revisionId) throws SQLException {
        statement.execute("INSERT INTO quiz_attempt (member_id, creator_id, quiz_id, revision_id, request_id, "
                + "request_fingerprint, revision_sha256, snapshot_sha256, title, video_id, question_count, correct_count, "
                + "reward_amount, reward_request_id, reward_status, submitted_at) VALUES (8, 1, " + quizId + ", " + revisionId
                + ", UUID(), REPEAT('f', 64), SHA2('revision-" + revisionId + "', 256), REPEAT('s', 64), '제목', "
                + "'dQw4w9WgXcQ', 1, 0, 0, NULL, 'NOT_ELIGIBLE', UTC_TIMESTAMP(6))");
    }

    private static Map<Long, String> revisionHashes(String database) throws SQLException {
        Map<Long, String> hashes = new HashMap<>();
        try (Connection connection = connect(database); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT revision_id, revision_sha256 FROM quiz_revision")) {
            while (rows.next()) {
                hashes.put(rows.getLong(1), rows.getString(2));
            }
        }
        return hashes;
    }

    private static String lock(String database, int quizId) throws SQLException {
        return query(database, "SELECT CONCAT(content_locked, ':', IFNULL(locked_revision_id, '-'), ':', "
                + "IFNULL(locked_revision_sha256, '-')) FROM quiz WHERE quiz_id = " + quizId);
    }

    /** 첫 행 첫 열. 값이 NULL이면 빈 문자열이다. */
    private static String query(String database, String sql) throws SQLException {
        try (Connection connection = connect(database); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                throw new IllegalStateException("결과 행이 없습니다: " + sql);
            }
            String value = rows.getString(1);
            return value == null ? "" : value;
        }
    }
}
