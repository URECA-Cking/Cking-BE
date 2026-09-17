package kr.co.cking.mission;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MissionRepository extends JpaRepository<Mission, Long> {

    boolean existsByMissionIdAndCreatorId(Long missionId, Long creatorId);
}
