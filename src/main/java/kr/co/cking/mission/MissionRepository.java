package kr.co.cking.mission;

import kr.co.cking.mission.domain.MissionType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MissionRepository extends JpaRepository<Mission, Long> {

    boolean existsByMissionIdAndCreatorId(Long missionId, Long creatorId);

    Optional<Mission> findByMissionIdAndCreatorId(Long missionId, Long creatorId);

    List<Mission> findByCreatorIdAndTypeIn(Long creatorId, Collection<MissionType> types);
}
