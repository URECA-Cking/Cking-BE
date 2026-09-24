package kr.co.cking.creator.repository;

import kr.co.cking.creator.domain.CreatorSpace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CreatorSpaceRepository extends JpaRepository<CreatorSpace, Long> {

    Optional<CreatorSpace> findByCreatorId(Long creatorId);
}
