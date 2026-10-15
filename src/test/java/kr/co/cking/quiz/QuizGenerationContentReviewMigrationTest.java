package kr.co.cking.quiz;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V77(이슈 #602) 원문 검토 컬럼·CHECK 검증. 공용 테스트 DB와 분리된 MySQL 컨테이너를 쓴다. Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
class QuizGenerationContentReviewMigrationTest {

    private static final String ROOT_PASSWORD = "test";
    private static final MigrationVersion REVIEW_MIGRATION = MigrationVersion.fromVersion("77");
    private static final String SHA = "'" + "a".repeat(64) + "'";

    @Container
    private static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4.10")
            .withEnv("MYSQL_ROOT_PASSWORD", ROOT_PASSWORD)
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+00:00")
            .withExposedPorts(3306)
            .waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    @Test
    void 기존_작업과_Quiz_데이터는_바뀌지_않고_검토_컬럼은_비어_있다() throws SQLException {
        String database = "quiz_generation_review_legacy";
        MigrationVersion previous = migrateToPreviousVersion(database);
        String[] tables = {"quiz", "quiz_revision", "quiz_question", "quiz_option", "quiz_revision_decision",
                "quiz_judge_job", "quiz_judge_result", "quiz_attempt"};
        Map<String, String> checksumsBefore;
        String generationsBefore;
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            baseData(statement, 3);
            quiz(statement, 1);
            legacy(statement, 1, "PENDING", null, false, false, null);
            legacy(statement, 2, "RUNNING", null, true, false, null);
            legacy(statement, 3, "SUCCEEDED", null, true, true, 1);
            legacy(statement, 1, "FAILED", "PROVIDER_ERROR", true, true, null);
            legacy(statement, 1, "UNKNOWN", "STALE_RUNNING", true, true, null);
            checksumsBefore = checksums(statement, tables);
            generationsBefore = generations(statement);
        }

        migrate(database, null);

        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            assertThat(query(statement, "SELECT CONCAT(COUNT(*), ':', MIN(success)) FROM flyway_schema_history "
                    + "WHERE version + 0 > " + previous + " AND version + 0 <= " + REVIEW_MIGRATION)).isEqualTo("1:1");
            assertThat(query(statement, "SELECT success FROM flyway_schema_history WHERE version = '"
                    + REVIEW_MIGRATION + "'")).isEqualTo("1");
            assertThat(generations(statement)).isEqualTo(generationsBefore);
            assertThat(query(statement, "SELECT COUNT(*) FROM quiz_generation WHERE review_stage IS NOT NULL "
                    + "OR ai_content_text IS NOT NULL OR grounding_facts IS NOT NULL OR grounding_model IS NOT NULL "
                    + "OR grounding_prompt_version IS NOT NULL OR reviewed_content_text IS NOT NULL "
                    + "OR reviewed_content_sha256 IS NOT NULL OR content_confirmed_by_member_id IS NOT NULL "
                    + "OR content_confirmed_at IS NOT NULL OR generation_started_at IS NOT NULL")).isEqualTo("0");
            assertThat(checksums(statement, tables)).isEqualTo(checksumsBefore);
            // 이전 버전 서버가 새 컬럼 없이 넣는 작업도 그대로 받는다.
            legacy(statement, 1, "FAILED", "DISPATCH_REJECTED", false, true, null);
        }
    }

    @Test
    void 단계별로_가능한_원문_검토_조합만_저장된다() throws SQLException {
        String database = "quiz_generation_review_checks";
        migrate(database, null);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            baseData(statement, 1);
            quiz(statement, 1);

            for (Map<String, String> row : List.of(
                    row("GROUNDING", "PENDING"),
                    terminal(row("GROUNDING", "FAILED"), "GROUNDING_INVALID"),
                    terminal(row("GROUNDING", "UNKNOWN"), "STALE_RUNNING"),
                    awaiting("RUNNING"),
                    terminal(awaiting("FAILED"), "CANCELLED"),
                    terminal(awaiting("UNKNOWN"), "STALE_RUNNING"),
                    generating("RUNNING"),
                    with(generating("RUNNING"), "generation_started_at", "UTC_TIMESTAMP(6)"),
                    succeeded(with(generating("SUCCEEDED"), "generation_started_at", "UTC_TIMESTAMP(6)")),
                    with(awaiting("RUNNING"), "grounding_facts", "'" + facts(200) + "'"),
                    with(awaiting("RUNNING"), "grounding_facts", "'[]'"))) {
                insert(statement, row);
                statement.execute("DELETE FROM quiz_generation");
            }

            assertRejected(statement, with(row("PREPARING", "RUNNING"), groundingResult()),
                    "ck_quiz_generation_review_status");
            assertRejected(statement, succeeded(row("GROUNDING", "SUCCEEDED")), "ck_quiz_generation_review_status");
            assertRejected(statement, awaiting("PENDING"), "ck_quiz_generation_review_status");
            assertRejected(statement, succeeded(awaiting("SUCCEEDED")), "ck_quiz_generation_review_status");
            assertRejected(statement, generating("PENDING"), "ck_quiz_generation_review_status");
            assertRejected(statement, succeeded(generating("SUCCEEDED")), "ck_quiz_generation_review_status");

            assertRejected(statement, with(row("GROUNDING", "RUNNING"), groundingResult()),
                    "ck_quiz_generation_review_grounding");
            assertRejected(statement, with(row(null, "RUNNING"), groundingResult()),
                    "ck_quiz_generation_review_grounding");
            assertRejected(statement, with(awaiting("RUNNING"), "ai_content_text", "NULL"),
                    "ck_quiz_generation_review_grounding");
            for (String column : new String[]{"grounding_facts", "grounding_model", "grounding_prompt_version"}) {
                assertRejected(statement, with(awaiting("RUNNING"), column, "NULL"),
                        "ck_quiz_generation_review_grounding");
            }
            assertRejected(statement, with(awaiting("RUNNING"), "grounding_facts", "'" + facts(201) + "'"),
                    "ck_quiz_generation_review_grounding");
            assertRejected(statement, with(awaiting("RUNNING"), "grounding_facts", "'{\"fact\":\"x\"}'"),
                    "ck_quiz_generation_review_grounding");
            assertRejected(statement, with(awaiting("RUNNING"), "grounding_facts", "'null'"),
                    "ck_quiz_generation_review_grounding");

            assertRejected(statement, with(generating("RUNNING"), "reviewed_content_text", "NULL",
                    "reviewed_content_sha256", "NULL", "content_confirmed_by_member_id", "NULL",
                    "content_confirmed_at", "NULL"), "ck_quiz_generation_review_confirmation");
            assertRejected(statement, with(awaiting("RUNNING"), confirmation()), "ck_quiz_generation_review_confirmation");
            assertRejected(statement, with(row(null, "RUNNING"), confirmation()), "ck_quiz_generation_review_confirmation");
            for (String column : new String[]{"reviewed_content_sha256", "content_confirmed_by_member_id",
                    "content_confirmed_at"}) {
                assertRejected(statement, with(generating("RUNNING"), column, "NULL"),
                        "ck_quiz_generation_review_confirmation");
            }

            assertRejected(statement, with(awaiting("RUNNING"), "generation_started_at", "UTC_TIMESTAMP(6)"),
                    "ck_quiz_generation_review_generation_started");
            assertRejected(statement, with(row(null, "RUNNING"), "generation_started_at", "UTC_TIMESTAMP(6)"),
                    "ck_quiz_generation_review_generation_started");

            assertThatThrownBy(() -> insert(statement, with(generating("RUNNING"), "content_confirmed_by_member_id", "999")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("fk_quiz_generation_content_confirmer");
            assertThat(query(statement, "SELECT COUNT(*) FROM quiz_generation")).isEqualTo("0");
        }
    }

    @Test
    void 검토_대기도_Creator당_진행_작업_하나로_제한된다() throws SQLException {
        String database = "quiz_generation_review_active";
        migrate(database, null);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            baseData(statement, 1);
            insert(statement, awaiting("RUNNING"));

            assertThatThrownBy(() -> insert(statement, row(null, "PENDING")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uk_quiz_generation_active_creator");
            assertThatThrownBy(() -> insert(statement, row("GROUNDING", "PENDING")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uk_quiz_generation_active_creator");

            statement.execute("UPDATE quiz_generation SET status = 'FAILED', failure_code = 'CANCELLED', "
                    + "completed_at = UTC_TIMESTAMP(6)");
            insert(statement, row("GROUNDING", "PENDING"));
        }
    }

    /** 이전 버전 서버의 오래된 작업 정리 UPDATE(review_stage를 보지 않음)가 어느 검토 단계에서도 성공해야 한다. */
    @Test
    void 이전_버전의_오래된_작업_정리가_검토_단계_작업에도_적용된다() throws SQLException {
        String database = "quiz_generation_review_rollback";
        migrate(database, null);
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            baseData(statement, 4);
            insert(statement, with(row("GROUNDING", "PENDING"), "creator_id", "1"));
            insert(statement, with(row("GROUNDING", "RUNNING"), "creator_id", "2"));
            insert(statement, with(awaiting("RUNNING"), "creator_id", "3"));
            insert(statement, with(generating("RUNNING"), "creator_id", "4", "generation_started_at",
                    "UTC_TIMESTAMP(6)"));
            statement.execute("UPDATE quiz_generation SET requested_at = requested_at - INTERVAL 1 DAY, "
                    + "started_at = started_at - INTERVAL 1 DAY");

            for (int creatorId = 1; creatorId <= 4; creatorId++) {
                statement.execute("UPDATE quiz_generation SET status = 'FAILED', failure_code = 'STALE_PENDING', "
                        + "completed_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6) WHERE creator_id = " + creatorId
                        + " AND status = 'PENDING' AND requested_at < UTC_TIMESTAMP(6) - INTERVAL 10 MINUTE");
                statement.execute("UPDATE quiz_generation SET status = 'UNKNOWN', failure_code = 'STALE_RUNNING', "
                        + "completed_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6) WHERE creator_id = " + creatorId
                        + " AND status = 'RUNNING' AND started_at < UTC_TIMESTAMP(6) - INTERVAL 15 MINUTE");
            }

            assertThat(query(statement, "SELECT GROUP_CONCAT(CONCAT(review_stage, ':', status, ':', failure_code) "
                    + "ORDER BY creator_id) FROM quiz_generation")).isEqualTo("GROUNDING:FAILED:STALE_PENDING,"
                    + "GROUNDING:UNKNOWN:STALE_RUNNING,AWAITING_REVIEW:UNKNOWN:STALE_RUNNING,"
                    + "GENERATING:UNKNOWN:STALE_RUNNING");
            assertThat(query(statement, "SELECT COUNT(*) FROM quiz_generation WHERE ai_content_text IS NOT NULL"))
                    .as("정리돼도 저장한 원문은 남는다").isEqualTo("2");
        }
    }

    private static Map<String, String> row(String stage, String status) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("creator_id", "1");
        row.put("requested_by_member_id", "7");
        row.put("request_id", "UUID()");
        row.put("request_fingerprint", SHA);
        row.put("video_id", "'dQw4w9WgXcQ'");
        row.put("title", "'제목'");
        row.put("question_count", "3");
        row.put("option_count", "4");
        row.put("status", "'" + status + "'");
        row.put("review_stage", stage == null ? "NULL" : "'" + stage + "'");
        row.put("requested_at", "UTC_TIMESTAMP(6)");
        row.put("started_at", "PENDING".equals(status) ? "NULL" : "UTC_TIMESTAMP(6)");
        row.put("updated_at", "UTC_TIMESTAMP(6)");
        return row;
    }

    private static Map<String, String> awaiting(String status) {
        return with(row("AWAITING_REVIEW", status), groundingResult());
    }

    private static Map<String, String> generating(String status) {
        return with(with(row("GENERATING", status), groundingResult()), confirmation());
    }

    private static String[] groundingResult() {
        return new String[]{"ai_content_text", "'AI 원문'", "grounding_facts", "'" + facts(1) + "'",
                "grounding_model", "'gemini-3.8-flash'", "grounding_prompt_version", "'video-grounding-v1'"};
    }

    private static String[] confirmation() {
        return new String[]{"reviewed_content_text", "'확정 원문'", "reviewed_content_sha256", SHA,
                "content_confirmed_by_member_id", "7", "content_confirmed_at", "UTC_TIMESTAMP(6)"};
    }

    private static Map<String, String> terminal(Map<String, String> row, String failure) {
        return with(row, "failure_code", "'" + failure + "'", "completed_at", "UTC_TIMESTAMP(6)");
    }

    private static Map<String, String> succeeded(Map<String, String> row) {
        return with(row, "quiz_id", "1", "revision_id", "1", "completed_at", "UTC_TIMESTAMP(6)");
    }

    private static Map<String, String> with(Map<String, String> row, String... columnValues) {
        Map<String, String> copy = new LinkedHashMap<>(row);
        for (int i = 0; i < columnValues.length; i += 2) {
            copy.put(columnValues[i], columnValues[i + 1]);
        }
        return copy;
    }

    private static String facts(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> "{\"fact\":\"사실 " + i + "\",\"evidenceType\":\"SPEECH\",\"evidence\":\"근거\","
                        + "\"timestampStart\":\"00:01\",\"timestampEnd\":\"00:02\"}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static void insert(Statement statement, Map<String, String> row) throws SQLException {
        statement.execute("INSERT INTO quiz_generation (" + String.join(", ", row.keySet()) + ") VALUES ("
                + String.join(", ", row.values()) + ")");
    }

    /** 반례는 확인하려는 CHECK 하나만 어기도록 만들고, 저장되지 않았는지도 확인한다. */
    private static void assertRejected(Statement statement, Map<String, String> row, String constraint)
            throws SQLException {
        assertThatThrownBy(() -> insert(statement, row))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining(constraint);
        assertThat(query(statement, "SELECT COUNT(*) FROM quiz_generation")).isEqualTo("0");
    }

    /** 이전 버전 서버가 넣는 형태의 작업. 새 컬럼을 쓰지 않는다. */
    private static void legacy(Statement statement, int creatorId, String status, String failure, boolean started,
                               boolean completed, Integer quizId) throws SQLException {
        String now = "UTC_TIMESTAMP(6)";
        String quiz = quizId == null ? "NULL" : String.valueOf(quizId);
        statement.execute("INSERT INTO quiz_generation (creator_id, requested_by_member_id, request_id, "
                + "request_fingerprint, video_id, title, question_count, option_count, status, failure_code, quiz_id, "
                + "revision_id, requested_at, started_at, completed_at, updated_at) VALUES (" + creatorId + ", 7, UUID(), "
                + SHA + ", 'dQw4w9WgXcQ', '제목', 3, 4, '" + status + "', " + (failure == null ? "NULL" : "'" + failure + "'")
                + ", " + quiz + ", " + quiz + ", " + now + ", " + (started ? now : "NULL") + ", "
                + (completed ? now : "NULL") + ", " + now + ")");
    }

    private static void baseData(Statement statement, int creatorCount) throws SQLException {
        statement.execute("INSERT INTO member (member_id, name, role) VALUES (7, 'creator', 'USER')");
        for (int creatorId = 1; creatorId <= creatorCount; creatorId++) {
            int memberId = creatorId == 1 ? 7 : 100 + creatorId;
            if (creatorId > 1) {
                statement.execute("INSERT INTO member (member_id, name, role) VALUES (" + memberId + ", 'c', 'USER')");
            }
            statement.execute("INSERT INTO creator (creator_id, member_id, name, activity_name, activity_name_normalized) "
                    + "VALUES (" + creatorId + ", " + memberId + ", 'c" + creatorId + "', 'c" + creatorId + "', 'c"
                    + creatorId + "')");
        }
    }

    private static void quiz(Statement statement, int quizId) throws SQLException {
        statement.execute("INSERT INTO quiz (quiz_id, creator_id, source_video_id, content_text, content_text_sha256, "
                + "publication_status, created_at, updated_at) VALUES (" + quizId + ", 1, 'dQw4w9WgXcQ', '원문', "
                + "SHA2('원문', 256), 'NOT_PUBLISHED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
        statement.execute("INSERT INTO quiz_revision (revision_id, quiz_id, revision_no, title, prompt_version, "
                + "question_count, option_count, revision_sha256, origin, hash_version, created_at) VALUES (" + quizId
                + ", " + quizId + ", 1, '제목', 'quiz-mcq-v1', 1, 4, SHA2('revision', 256), 'AI_GENERATED', "
                + "'quiz-revision-v1', UTC_TIMESTAMP(6))");
    }

    private static Map<String, String> checksums(Statement statement, String[] tables) throws SQLException {
        Map<String, String> checksums = new LinkedHashMap<>();
        for (String table : tables) {
            checksums.put(table, query(statement, "SELECT CONCAT(COUNT(*), ':', IFNULL(BIT_XOR(CRC32(CONCAT_WS('|', "
                    + Arrays.stream(columns(statement, table)).map(column -> "`" + column + "`")
                    .collect(Collectors.joining(", ")) + "))), 0)) FROM "
                    + table));
        }
        return checksums;
    }

    private static String[] columns(Statement statement, String table) throws SQLException {
        return query(statement, "SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY ORDINAL_POSITION) FROM "
                + "information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "'")
                .split(",");
    }

    private static String generations(Statement statement) throws SQLException {
        return query(statement, "SELECT GROUP_CONCAT(CONCAT_WS('|', generation_id, creator_id, request_id, status, "
                + "IFNULL(failure_code, '-'), IFNULL(quiz_id, '-'), IFNULL(revision_id, '-'), requested_at, "
                + "IFNULL(started_at, '-'), IFNULL(completed_at, '-'), updated_at) ORDER BY generation_id) "
                + "FROM quiz_generation");
    }

    /** V77 바로 앞 버전까지 적용하고 그 버전을 돌려준다. 다른 PR이 V76을 추가해도 그대로 동작한다. */
    private static MigrationVersion migrateToPreviousVersion(String database) throws SQLException {
        createDatabase(database);
        Flyway flyway = flyway(database, null);
        MigrationVersion previous = Arrays.stream(flyway.info().all())
                .map(MigrationInfo::getVersion)
                .filter(version -> version.compareTo(REVIEW_MIGRATION) < 0)
                .max(MigrationVersion::compareTo)
                .orElseThrow();
        flyway(database, previous).migrate();
        try (Connection connection = connect(database); Statement statement = connection.createStatement()) {
            assertThat(query(statement, "SELECT COUNT(*) FROM flyway_schema_history WHERE version + 0 >= "
                    + REVIEW_MIGRATION)).isEqualTo("0");
        }
        return previous;
    }

    private static void migrate(String database, MigrationVersion target) throws SQLException {
        createDatabase(database);
        flyway(database, target).migrate();
    }

    private static Flyway flyway(String database, MigrationVersion target) {
        var configuration = Flyway.configure().dataSource(url(database), "root", ROOT_PASSWORD)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static void createDatabase(String database) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url(""), "root", ROOT_PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS " + database);
        }
    }

    private static String url(String database) {
        return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + database
                + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&allowPublicKeyRetrieval=true&useSSL=false";
    }

    private static Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(url(database), "root", ROOT_PASSWORD);
    }

    /** 첫 행 첫 열. 값이 NULL이면 빈 문자열이다. */
    private static String query(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                throw new IllegalStateException("결과 행이 없습니다: " + sql);
            }
            String value = rows.getString(1);
            return value == null ? "" : value;
        }
    }
}
