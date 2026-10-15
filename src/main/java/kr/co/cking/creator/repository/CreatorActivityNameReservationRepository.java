package kr.co.cking.creator.repository;

import java.util.Optional;
import kr.co.cking.creator.domain.CreatorActivityNameReservation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreatorActivityNameReservationRepository extends JpaRepository<CreatorActivityNameReservation, Long> {

    Optional<CreatorActivityNameReservation> findByActivityNameNormalized(String activityNameNormalized);
}
