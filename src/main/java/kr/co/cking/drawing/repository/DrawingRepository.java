package kr.co.cking.drawing.repository;

import java.util.Optional;
import kr.co.cking.drawing.domain.Drawing;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DrawingRepository extends JpaRepository<Drawing, Long> {

    Optional<Drawing> findByEventIdAndDrawNo(Long eventId, int drawNo);

    boolean existsByEventIdAndDrawNo(Long eventId, int drawNo);
}
