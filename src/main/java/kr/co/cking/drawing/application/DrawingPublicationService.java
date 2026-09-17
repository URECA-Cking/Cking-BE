package kr.co.cking.drawing.application;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingErrorCode;
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.drawing.domain.DrawingVisibility;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.event.application.EventDrawingQueryService;
import kr.co.cking.event.application.dto.EventDrawingSource;
import kr.co.cking.event.application.service.EventCommandService;
import kr.co.cking.event.domain.EventErrorCode;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.member.application.MemberQueryService;
import lombok.RequiredArgsConstructor;

/**
 * 관리자의 추첨 결과 공개 요청을 처리한다(통합 API 명세 v2.5 No.35, 취합v1.5.4 §12,
 * FR-P2-044). 공개는 Event.status가 {@code DRAW_COMPLETED}이고 공식 INITIAL Drawing이
 * {@code COMPLETED}+{@code PRIVATE}인 경우에만 허용하며, Drawing 공개와 Event
 * {@code DRAW_COMPLETED→PUBLISHED} 전이를 한 Tx로 묶어 둘 다 반영되거나 둘 다 반영되지
 * 않도록 한다. REDRAW 공개(FR-P4-115)는 Event가 이미 PUBLISHED 상태이므로 이 API의 대상이
 * 아니다(시스템4 담당, 별도 API).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class DrawingPublicationService {

    private final DrawingRepository drawingRepository;
    private final EventDrawingQueryService eventDrawingQueryService;
    private final EventCommandService eventCommandService;
    private final MemberQueryService memberQueryService;
    private final Clock clock;

    /**
     * 이미 공개된 Drawing이면 상태를 바꾸지 않고 현재 상태를 그대로 반환한다(중복 공개 요청 멱등
     * 처리). 이때도 Event가 실제로 PUBLISHED인지 재확인해, Drawing만 PUBLIC이고 Event는
     * PUBLISHED로 전환되지 않은 불일치 상태를 성공으로 위장하지 않는다.
     */
    public Drawing publish(Long drawingId, Long adminId) {
        memberQueryService.validateAdmin(adminId);

        Drawing drawing = drawingRepository.findById(drawingId)
                .orElseThrow(() -> new BusinessException(DrawingErrorCode.DRAWING_NOT_FOUND));
        if (drawing.getDrawType() != DrawingType.INITIAL) {
            throw new BusinessException(DrawingErrorCode.DRAWING_TYPE_NOT_SUPPORTED);
        }

        EventDrawingSource event = eventDrawingQueryService.getDrawingSource(drawing.getEventId());

        if (drawing.getVisibility() == DrawingVisibility.PUBLIC) {
            if (event.status() != EventStatus.PUBLISHED) {
                throw new BusinessException(EventErrorCode.INVALID_STATE);
            }
            return drawing;
        }

        if (event.status() != EventStatus.DRAW_COMPLETED) {
            throw new BusinessException(EventErrorCode.INVALID_STATE);
        }

        drawing.publish(clock.instant());
        eventCommandService.publish(drawing.getEventId());
        return drawing;
    }
}
