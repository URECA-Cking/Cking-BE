package kr.co.cking.common.repository;

import java.sql.Timestamp;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 인스턴스 시계 차이 없이 같은 DB의 UTC 마이크로초 시각을 사용한다. */
@Repository
@RequiredArgsConstructor
public class DatabaseTime {
    private final JdbcTemplate jdbc;

    public Instant now() {
        return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", Timestamp.class).toInstant();
    }
}
