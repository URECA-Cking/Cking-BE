package kr.co.cking.ticket.application;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** 지급 데이터는 읽기만 하며 알림 전용 메타데이터만 변경한다. */
@Repository
@RequiredArgsConstructor
public class RewardNotificationLedgerStore {
    private final JdbcTemplate jdbc;

    public record Target(long ledgerId, long memberId, long amount, String rewardType, String creatorName) { }

    @Transactional(readOnly = true, timeout = 5)
    public List<Long> findDue(boolean common, int limit) {
        return jdbc.queryForList("SELECT ledger_id FROM " + table(common)
                + " WHERE reward_notification_state=1 AND type='EARN'"
                + " AND reward_notification_retry_at<=UTC_TIMESTAMP(6)"
                + " ORDER BY reward_notification_retry_at,ledger_id LIMIT ?", Long.class, limit);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Target> lock(boolean common, long id) {
        // 원장만 먼저 잠근다. 부가 정보를 얻기 위한 JOIN은 다른 도메인 행을 잠그지 않는다.
        List<Long> found = jdbc.queryForList("SELECT ledger_id FROM " + table(common)
                + " WHERE ledger_id=? AND reward_notification_state=1 AND type='EARN'"
                + " AND reward_notification_retry_at<=UTC_TIMESTAMP(6) FOR UPDATE", Long.class, id);
        if (found.isEmpty()) return Optional.empty();
        String query = common ? """
                SELECT l.ledger_id,l.member_id,l.delta_amount,COALESCE(m.type,r.reward_type) reward_type,NULL creator_name
                FROM common_ticket_ledger l
                LEFT JOIN common_mission_completion mc ON mc.completion_id=l.mission_completion_id
                LEFT JOIN common_mission m ON m.mission_id=mc.mission_id
                LEFT JOIN ticket_common_once_earn_request r ON r.once_earn_request_id=l.once_earn_request_id
                WHERE l.ledger_id=?
                """ : """
                SELECT l.ledger_id,l.member_id,l.delta_amount,
                    CASE WHEN l.quiz_reward_request_id IS NOT NULL THEN 'AI_QUIZ' ELSE m.type END reward_type,
                    c.activity_name creator_name
                FROM ticket_ledger l
                JOIN creator c ON c.creator_id=l.creator_id
                LEFT JOIN mission_completion mc ON mc.completion_id=l.mission_completion_id
                LEFT JOIN mission m ON m.mission_id=mc.mission_id
                WHERE l.ledger_id=?
                """;
        return jdbc.query(query, (r, row) -> new Target(r.getLong("ledger_id"), r.getLong("member_id"),
                r.getLong("delta_amount"), r.getString("reward_type"), r.getString("creator_name")), id)
                .stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(boolean common, long id, long notificationId) {
        int updated = jdbc.update("UPDATE " + table(common)
                + " SET reward_notification_state=2,reward_notification_id=? WHERE ledger_id=? AND reward_notification_state=1",
                notificationId, id);
        if (updated != 1) throw new IllegalStateException("알림 완료 상태가 변경되었습니다.");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void exclude(boolean common, long id) {
        jdbc.update("UPDATE " + table(common) + " SET reward_notification_state=0"
                + " WHERE ledger_id=? AND reward_notification_state=1", id);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void defer(boolean common, long id) {
        jdbc.update("UPDATE " + table(common)
                + " SET reward_notification_retry_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 60 SECOND)"
                + " WHERE ledger_id=? AND reward_notification_state=1", id);
    }

    private static String table(boolean common) { return common ? "common_ticket_ledger" : "ticket_ledger"; }
}
