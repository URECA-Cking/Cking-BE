package kr.co.cking.drawing.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingErrorCode;
import kr.co.cking.drawing.domain.DrawingStatus;
import kr.co.cking.drawing.domain.DrawingVisibility;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.winner.repository.WinnerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DrawingPublicQueryService {
    private static final int INITIAL_DRAW_NO = 0;
    private final DrawingRepository drawingRepository;
    private final WinnerRepository winnerRepository;

    public PublicDrawingResult getPublishedWinners(Long eventId) {
        Drawing drawing = drawingRepository.findByEventIdAndDrawNoAndVisibility(
                        eventId, INITIAL_DRAW_NO, DrawingVisibility.PUBLIC)
                .orElseThrow(() -> new BusinessException(DrawingErrorCode.DRAWING_NOT_FOUND));
        if (drawing.getStatus() != DrawingStatus.COMPLETED) {
            throw new BusinessException(DrawingErrorCode.DRAWING_NOT_COMPLETED);
        }
        return new PublicDrawingResult(eventId, drawing.getId(), winnerRepository
                .findAllByDrawingIdOrderByRankInDrawingAsc(drawing.getId()).stream()
                .map(PublicWinnerResult::from).toList());
    }
}
