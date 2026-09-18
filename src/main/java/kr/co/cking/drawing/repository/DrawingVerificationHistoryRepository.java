package kr.co.cking.drawing.repository;

import kr.co.cking.drawing.domain.DrawingVerificationHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DrawingVerificationHistoryRepository
        extends JpaRepository<DrawingVerificationHistory, Long> {

    Page<DrawingVerificationHistory> findAllByDrawingId(Long drawingId, Pageable pageable);

    long countByDrawingId(Long drawingId);
}
