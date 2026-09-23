package kr.co.cking.mission;

import kr.co.cking.mission.domain.CommonMissionType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CommonMissionRepository extends JpaRepository<CommonMission, Long> {

    Optional<CommonMission> findByMissionId(Long missionId);

    List<CommonMission> findByTypeIn(Collection<CommonMissionType> types);
}
