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
     */
    public boolean existsUnresolvedByMemberAndCreator(Long memberId, Long creatorId) {
        return jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM dead_stream_message
                            WHERE resolution_status = 'UNRESOLVED'
                              AND payload ->> '$.userId' = :memberId
                              AND payload ->> '$.creatorId' = :creatorId
                        )
                        """)
                .param("memberId", String.valueOf(memberId))
                .param("creatorId", String.valueOf(creatorId))
                .query(Boolean.class)
                .single();
    }
}
