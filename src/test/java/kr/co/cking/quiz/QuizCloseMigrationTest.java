package kr.co.cking.quiz;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** V82의 기존 데이터에 V83만 추가 적용해 상태·포인터·잠금 보존과 CHECK를 검증한다. */
@Testcontainers
class QuizCloseMigrationTest {
    @Container static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4.10")
            .withEnv("MYSQL_ROOT_PASSWORD", "test").withEnv("MYSQL_DATABASE", "close_migration")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci")
            .withExposedPorts(3306).waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    @Test void 기존_게시_중지_미게시_데이터를_보존하고_NULL_우회를_차단한다() throws Exception {
        String url = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/close_migration";
        Flyway.configure().dataSource(url, "root", "test").locations("classpath:db/migration").target("82").load().migrate();
        try (Connection c = DriverManager.getConnection(url, "root", "test"); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO member(member_id,name,role) VALUES(1,'creator','USER')");
            s.executeUpdate("INSERT INTO creator(creator_id,member_id,name,activity_name,activity_name_normalized) VALUES(1,1,'creator','creator','creator')");
            for (int id = 1; id <= 4; id++) {
                s.executeUpdate("INSERT INTO quiz(quiz_id,creator_id,source_video_id,content_text,content_text_sha256,publication_status,created_at,updated_at) VALUES("
                        + id + ",1,'dQw4w9WgXcQ','원문',SHA2('원문',256),'NOT_PUBLISHED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
                s.executeUpdate("INSERT INTO quiz_revision(revision_id,quiz_id,revision_no,title,prompt_version,question_count,option_count,revision_sha256,origin,hash_version,created_at) VALUES("
                        + id + "," + id + ",1,'제목','v1',1,4,SHA2('revision" + id + "',256),'AI_GENERATED','quiz-revision-v1',UTC_TIMESTAMP(6))");
            }
            s.executeUpdate("UPDATE quiz SET publication_status='PUBLISHED',published_revision_id=1,published_at=UTC_TIMESTAMP(6),content_locked=TRUE,locked_revision_id=1,locked_revision_sha256=SHA2('revision1',256) WHERE quiz_id=1");
            s.executeUpdate("UPDATE quiz SET publication_status='UNPUBLISHED',published_at=UTC_TIMESTAMP(6),unpublished_at=UTC_TIMESTAMP(6),content_locked=TRUE,locked_revision_id=2,locked_revision_sha256=SHA2('revision2',256) WHERE quiz_id=2");
            s.executeUpdate("UPDATE quiz SET published_at=UTC_TIMESTAMP(6),content_locked=TRUE WHERE quiz_id=3");
            List<String> before = snapshot(s);
            var result = Flyway.configure().dataSource(url, "root", "test").locations("classpath:db/migration").target("83").load().migrate();
            assertThat(result.migrationsExecuted).isEqualTo(1);
            assertThat(snapshot(s)).isEqualTo(before);
            try (ResultSet rows = s.executeQuery("SELECT COUNT(*) FROM quiz WHERE closed_at IS NOT NULL")) {
                rows.next(); assertThat(rows.getInt(1)).isZero();
            }
            assertThatThrownBy(() -> s.executeUpdate("UPDATE quiz SET publication_status='CLOSED' WHERE quiz_id=1")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> s.executeUpdate("UPDATE quiz SET closed_at=UTC_TIMESTAMP(6) WHERE quiz_id=1")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> s.executeUpdate("UPDATE quiz SET publication_status='CLOSED',closed_at=UTC_TIMESTAMP(6) WHERE quiz_id=4")).isInstanceOf(SQLException.class);
            s.executeUpdate("UPDATE quiz SET publication_status='CLOSED',closed_at=UTC_TIMESTAMP(6) WHERE quiz_id IN (1,2)");
            try (ResultSet rows = s.executeQuery("SELECT COUNT(*) FROM quiz WHERE publication_status='CLOSED' AND ((quiz_id=1 AND published_revision_id=1) OR (quiz_id=2 AND published_revision_id IS NULL))")) {
                rows.next(); assertThat(rows.getInt(1)).isEqualTo(2);
            }
            assertThatThrownBy(() -> s.executeUpdate("UPDATE quiz SET content_locked=FALSE WHERE quiz_id=1")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> s.executeUpdate("UPDATE quiz SET publication_status='PUBLISHED' WHERE quiz_id=2")).isInstanceOf(SQLException.class);
        }
    }
    private static List<String> snapshot(Statement s) throws SQLException {
        List<String> result = new ArrayList<>();
        try (ResultSet r = s.executeQuery("SELECT quiz_id,publication_status,published_revision_id,published_at,unpublished_at,content_locked,locked_revision_id,locked_revision_sha256,updated_at FROM quiz ORDER BY quiz_id")) {
            while (r.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= 9; i++) row.append(r.getString(i)).append('|');
                result.add(row.toString());
            }
        }
        return result;
    }
}
