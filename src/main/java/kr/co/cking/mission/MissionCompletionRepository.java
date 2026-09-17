package kr.co.cking.mission;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MissionCompletionRepository extends JpaRepository<MissionCompletion, Long> {

    Optional<MissionCompletion> findByRequestId(String requestId);
}
