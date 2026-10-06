package kr.co.cking.creator.application;

import java.util.Collection;
import java.util.List;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.member.domain.Member;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * 유사 추천 통합 테스트가 만든 Creator·Member에 딸린 행만 지운다. 공용 DB의 다른 데이터를 건드리지 않는다.
 * FK 순서(state → candidate → generation → follow)를 지킨다.
 */
final class SimilarityTestCleaner {

    private final NamedParameterJdbcTemplate jdbc;

    SimilarityTestCleaner(JdbcTemplate jdbcTemplate) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    void delete(Collection<Member> members, Collection<Creator> creators) {
        List<Long> memberIds = members.stream().map(Member::getMemberId).toList();
        List<Long> creatorIds = creators.stream().map(Creator::getCreatorId).toList();
        if (!creatorIds.isEmpty()) {
            var ids = new MapSqlParameterSource("creatorIds", creatorIds);
            jdbc.update("DELETE FROM creator_similarity_state WHERE creator_id IN (:creatorIds)", ids);
            jdbc.update("""
                    DELETE FROM creator_similarity_candidate
                    WHERE similar_creator_id IN (:creatorIds)
                       OR generation_id IN (SELECT generation_id FROM creator_similarity_generation
                                            WHERE creator_id IN (:creatorIds))
                    """, ids);
            jdbc.update("DELETE FROM creator_similarity_generation WHERE creator_id IN (:creatorIds)", ids);
        }
        if (!memberIds.isEmpty()) {
            jdbc.update("DELETE FROM creator_follow WHERE member_id IN (:memberIds)",
                    new MapSqlParameterSource("memberIds", memberIds));
        }
    }

    long generationCount(Long creatorId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM creator_similarity_generation WHERE creator_id = :id",
                new MapSqlParameterSource("id", creatorId), Long.class);
    }

    long candidateCount(Long creatorId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM creator_similarity_candidate
                WHERE generation_id IN (SELECT generation_id FROM creator_similarity_generation
                                        WHERE creator_id = :id)
                """, new MapSqlParameterSource("id", creatorId), Long.class);
    }
}
