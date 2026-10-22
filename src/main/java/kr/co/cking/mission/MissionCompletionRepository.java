package kr.co.cking.mission;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MissionCompletionRepository extends JpaRepository<MissionCompletion, Long> {

    Optional<MissionCompletion> findByRequestId(String requestId);

    List<MissionCompletion> findAllByMemberIdAndCreatorIdAndMissionIdInAndPeriodKey(
            Long memberId, Long creatorId, Collection<Long> missionIds, String periodKey);

    List<MissionCompletion> findAllByMemberIdAndCreatorIdAndMissionIdInAndCompletionKey(
            Long memberId, Long creatorId, Collection<Long> missionIds, String completionKey);

    @Query(value = """
            SELECT CASE WHEN EXISTS (
                SELECT 1
                FROM mission_completion mc
                JOIN mission m ON m.mission_id = mc.mission_id
                WHERE mc.member_id = :memberId
                  AND mc.period_key = :periodKey
                  AND m.type = :missionType
            ) THEN TRUE ELSE FALSE END
            """, nativeQuery = true)
    long existsByMemberIdAndPeriodKeyAndMissionType(
            @Param("memberId") Long memberId,
            @Param("periodKey") String periodKey,
            @Param("missionType") String missionType);
}
