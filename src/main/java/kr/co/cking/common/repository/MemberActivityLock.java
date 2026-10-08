package kr.co.cking.common.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 회원 FK가 참조하는 부모 행과 업무 직렬화용 잠금을 분리한다. 호출 트랜잭션이 잠금을 보유한다. */
@Repository
@RequiredArgsConstructor
public class MemberActivityLock {
    private final JdbcTemplate jdbc;

    public void lock(Long memberId) {
        jdbc.update("""
                INSERT INTO member_activity_lock (member_id) VALUES (?)
                ON DUPLICATE KEY UPDATE member_id = member_id
                """, memberId);
        jdbc.queryForObject("SELECT member_id FROM member_activity_lock WHERE member_id = ? FOR UPDATE",
                Long.class, memberId);
    }
}
