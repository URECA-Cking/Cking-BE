package kr.co.cking.abuse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** abuse_detection Flyway 마이그레이션의 실제 적용 결과를 검증한다. */
@SpringBootTest
class AbuseDetectionMigrationIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 탐지 결과 테이블의 컬럼, 외래 키, 상태 제약과 운영 조회 인덱스를 검증한다. */
    @Test
    void abuse_detection_테이블과_조회_인덱스를_생성한다() {
        Map<String, Object> table = jdbcTemplate.queryForMap(
                "SELECT TABLE_NAME, TABLE_COLLATION FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'abuse_detection'");
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'abuse_detection' "
                        + "ORDER BY ORDINAL_POSITION");
        Set<String> indexes = jdbcTemplate.queryForList(
                        "SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS "
                                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'abuse_detection'",
                        String.class)
                .stream()
                .collect(Collectors.toSet());
        String createTable = jdbcTemplate.queryForMap("SHOW CREATE TABLE abuse_detection")
                .values()
                .stream()
                .map(String::valueOf)
                .filter(value -> value.contains("CREATE TABLE"))
                .findFirst()
                .orElseThrow();

        assertThat(table).containsEntry("TABLE_NAME", "abuse_detection");
        assertThat(columns)
                .extracting(column -> column.get("COLUMN_NAME"))
                .containsExactly("id", "member_id", "abuse_type", "status", "detected_at", "reviewed_at", "reviewed_by", "evidence");
        assertThat(column(columns, "member_id"))
                .containsEntry("COLUMN_TYPE", "bigint")
                .containsEntry("IS_NULLABLE", "NO");
        assertThat(column(columns, "reviewed_at")).containsEntry("IS_NULLABLE", "YES");
        assertThat(column(columns, "reviewed_by")).containsEntry("IS_NULLABLE", "YES");
        assertThat(column(columns, "evidence"))
                .containsEntry("COLUMN_TYPE", "json")
                .containsEntry("IS_NULLABLE", "NO");
        assertThat(indexes).contains(
                "idx_abuse_detection_member_detected",
                "idx_abuse_detection_status_detected",
                "idx_abuse_detection_type_detected");
        assertThat(createTable)
                .contains("fk_abuse_detection_member")
                .contains("fk_abuse_detection_reviewer")
                .contains("ck_abuse_detection_status");
    }

    /** 지정한 컬럼의 메타데이터 행을 반환한다. */
    private Map<String, Object> column(List<Map<String, Object>> columns, String name) {
        return columns.stream()
                .filter(column -> name.equals(column.get("COLUMN_NAME")))
                .findFirst()
                .orElseThrow();
    }
}
