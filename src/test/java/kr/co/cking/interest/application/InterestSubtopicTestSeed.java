package kr.co.cking.interest.application;

import java.nio.file.Path;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * 세부 주제 초안(`subtopic-v1`, 85개)을 테스트 DB에 적재한다. 확정 전이라 Flyway 시드가 없으므로 테스트가 직접 넣는다.
 * 호출한 Transaction의 연결을 쓰므로 {@code @Transactional} 테스트에서는 롤백된다.
 */
final class InterestSubtopicTestSeed {

    static final String VERSION = "subtopic-v1";
    private static final Path SCRIPT = Path.of("src/test/resources/fixtures/taxonomy/subtopic-v1-seed.sql");

    private InterestSubtopicTestSeed() {
    }

    static void load(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(SCRIPT));
            return null;
        });
    }

    /** 커밋된 상태로 적재한 테스트(동시성)가 정리할 때 쓴다. 회원 선택이 이미 지워진 뒤에 호출한다. */
    static void remove(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("delete from interest_subtopic where subtopic_version = ?", VERSION);
        jdbcTemplate.update("delete from interest_subtopic_set where subtopic_version = ?", VERSION);
    }
}
