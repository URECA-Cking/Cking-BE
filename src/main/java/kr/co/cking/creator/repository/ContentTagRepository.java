package kr.co.cking.creator.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Map;

/** Creator-owned durable payloads, confirmation history and transactional outbox. */
@Repository
@RequiredArgsConstructor
public class ContentTagRepository {
    private final JdbcTemplate jdbc;
    public List<Map<String, Object>> rows(String sql, Object... args) { return jdbc.queryForList(sql, args); }
    public int update(String sql, Object... args) { return jdbc.update(sql, args); }
}
