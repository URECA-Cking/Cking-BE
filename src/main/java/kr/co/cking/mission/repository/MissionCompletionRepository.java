package kr.co.cking.mission.repository;

import kr.co.cking.mission.domain.MissionCompletion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MissionCompletionRepository extends JpaRepository<MissionCompletion, Long> {

    Optional<MissionCompletion> findByRequestId(String requestId);

    boolean existsByMemberIdAndCreatorIdAndMissionIdAndPeriodKey(
            Long memberId, Long creatorId, Long missionId, String periodKey);

    boolean existsByMemberIdAndCreatorIdAndMissionIdAndPeriodKeyAndRequestIdNot(
            Long memberId, Long creatorId, Long missionId, String periodKey, String requestId);

    /**
     * 미션 목록 API의 {@code completedToday} 배치 조회용. 미션마다 {@code existsBy...}를
     * 반복 호출하는 대신 크리에이터의 미션 전체에 대해 한 번만 조회한다(N+1 방지).
     * FR-P1-004(태연) 담당 API에서 사용한다.
     */
    List<MissionCompletion> findByMemberIdAndCreatorIdAndPeriodKeyAndMissionIdIn(
            Long memberId, Long creatorId, String periodKey, Collection<Long> missionIds);
}
