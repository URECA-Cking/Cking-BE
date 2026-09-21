package kr.co.cking.mission;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MissionCompletionRepository extends JpaRepository<MissionCompletion, Long> {

    Optional<MissionCompletion> findByRequestId(String requestId);

    List<MissionCompletion> findAllByMemberIdAndCreatorIdAndMissionIdInAndPeriodKey(
            Long memberId, Long creatorId, Collection<Long> missionIds, String periodKey);
}
