package kr.co.cking.creator.repository;

import kr.co.cking.creator.domain.CreatorSpaceSlugReservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CreatorSpaceSlugReservationRepository extends JpaRepository<CreatorSpaceSlugReservation, Long> {

    Optional<CreatorSpaceSlugReservation> findBySlug(String slug);
}
