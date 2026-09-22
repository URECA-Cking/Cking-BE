package kr.co.cking.mission;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CommonMissionCompletionRepository extends JpaRepository<CommonMissionCompletion, Long> {

    Optional<CommonMissionCompletion> findByRequestId(String requestId);

    List<CommonMissionCompletion> findAllByMemberIdAndMissionIdInAndPeriodKey(
            Long memberId, Collection<Long> missionIds, String periodKey);
}
