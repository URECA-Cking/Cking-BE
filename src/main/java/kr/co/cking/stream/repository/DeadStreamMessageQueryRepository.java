package kr.co.cking.stream.repository;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DeadStreamMessageQueryRepository {

    private final JdbcClient jdbcClient;

    public List<String> findUnresolvedSpendSourceStreamIds(Long eventId) {
        return jdbcClient.sql("""
                        SELECT source_stream_id
                        FROM dead_stream_message
                        WHERE event_id = :eventId
                          AND stream_type = 'SPEND'
                          AND resolution_status = 'UNRESOLVED'
                        """)
                .param("eventId", eventId)
                .query(String.class)
                .list();
    }

    /**
     * 해당 (memberId, creatorId)의 미해결 Dead Stream이 있는지 확인한다. EARN 행은 event_id가 없고
     * creator_id 컬럼도 없어서, 원본 Stream 필드를 보존한 payload의 userId·creatorId로 판별한다.
     *
     * <p>PR #247 리뷰(자비): couponType=COMMON인 SPEND 메시지도 payload에 creatorId가 실려
     * 있지만 그 크리에이터 잔액을 차감하지 않았으므로 제외한다(UnappliedBalanceMessageChecker의
     * PEL·미전달 검사와 동일 원칙, 이슈 #243) - 안 그러면 공용 응모권 사용 메시지 하나가 그
     * 크리에이터 잔액 수동 보정을 영구히 막는다. couponType 필드가 없는 메시지(EARN, 배포 전
     * SPEND)는 CREATOR로 취급해 그대로 포함한다.
     */
    public boolean existsUnresolvedByMemberAndCreator(Long memberId, Long creatorId) {
        return jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM dead_stream_message
                            WHERE resolution_status = 'UNRESOLVED'
                              AND payload ->> '$.userId' = :memberId
                              AND payload ->> '$.creatorId' = :creatorId
                              AND COALESCE(payload ->> '$.couponType', 'CREATOR') <> 'COMMON'
                        )
                        """)
                .param("memberId", String.valueOf(memberId))
                .param("creatorId", String.valueOf(creatorId))
                .query(Boolean.class)
                .single();
    }

    /**
     * 해당 memberId의 미해결 공용 응모권 Dead Stream이 있는지 확인한다(이슈 #256). 공용 EARN
     * ({@code COMMON_EARN})과 couponType=COMMON인 SPEND가 대상이며, 크리에이터 잔액 메시지는 제외한다.
     */
    public boolean existsUnresolvedCommonByMember(Long memberId) {
        return jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM dead_stream_message
                            WHERE resolution_status = 'UNRESOLVED'
                              AND payload ->> '$.userId' = :memberId
                              AND (stream_type = 'COMMON_EARN' OR payload ->> '$.couponType' = 'COMMON')
                        )
                        """)
                .param("memberId", String.valueOf(memberId))
                .query(Boolean.class)
                .single();
    }
}
