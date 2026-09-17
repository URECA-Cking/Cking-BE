package kr.co.cking.mission.repository;

import kr.co.cking.mission.domain.Mission;
import kr.co.cking.mission.domain.MissionType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MissionRepository extends JpaRepository<Mission, Long> {

    List<Mission> findByCreatorId(Long creatorId);

    Optional<Mission> findByMissionIdAndCreatorId(Long missionId, Long creatorId);

    Optional<Mission> findByCreatorIdAndType(Long creatorId, MissionType type);
}
