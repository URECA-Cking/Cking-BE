package kr.co.cking.winner.repository;

import java.util.List;
import kr.co.cking.winner.domain.Winner;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WinnerRepository extends JpaRepository<Winner, Long> {

    List<Winner> findAllByDrawingIdOrderByRankInDrawingAsc(Long drawingId);

    boolean existsByEventIdAndMemberId(Long eventId, Long memberId);
}
