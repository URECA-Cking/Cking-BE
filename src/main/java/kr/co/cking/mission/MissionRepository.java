package kr.co.cking.mission;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MissionRepository extends JpaRepository<Mission, Long> {

    boolean existsByMissionIdAndCreatorId(Long missionId, Long creatorId);

    Optional<Mission> findByMissionIdAndCreatorId(Long missionId, Long creatorId);
}
