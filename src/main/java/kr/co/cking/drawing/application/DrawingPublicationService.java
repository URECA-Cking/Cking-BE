package kr.co.cking.drawing.application;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingErrorCode;
import kr.co.cking.drawing.domain.DrawingVisibility;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.event.application.service.EventCommandService;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventErrorCode;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.event.repository.EventRepository;
import kr.co.cking.member.application.MemberQueryService;
import lombok.RequiredArgsConstructor;

/**
 * 관리자의 추첨 결과 공개 요청을 처리한다(통합 API 명세 v2.5 No.35, 취합v1.5.4 §12,
 * FR-P2-044). 공개는 Event.status가 {@code DRAW_COMPLETED}이고 공식 Drawing이
 * {@code COMPLETED}+{@code PRIVATE}인 경우에만 허용하며, Drawing 공개와 Event
 * {@code DRAW_COMPLETED→PUBLISHED} 전이를 한 Tx로 묶어 둘 다 반영되거나 둘 다 반영되지
 * 않도록 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class DrawingPublicationService {

    private final DrawingRepository drawingRepository;
    private final EventRepository eventRepository;
    private final EventCommandService eventCommandService;
    private final MemberQueryService memberQueryService;
    private final Clock clock;

    /** 이미 공개된 Drawing이면 상태를 바꾸지 않고 현재 상태를 그대로 반환한다(중복 공개 요청 멱등 처리). */
    public Drawing publish(Long drawingId, Long adminId) {
        memberQueryService.validateAdmin(adminId);

        Drawing drawing = drawingRepository.findById(drawingId)
                .orElseThrow(() -> new BusinessException(DrawingErrorCode.DRAWING_NOT_FOUND));

        if (drawing.getVisibility() == DrawingVisibility.PUBLIC) {
            return drawing;
        }

        Event event = eventRepository.findById(drawing.getEventId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (event.getStatus() != EventStatus.DRAW_COMPLETED) {
            throw new BusinessException(EventErrorCode.INVALID_STATE);
        }

        drawing.publish(clock.instant());
        eventCommandService.publish(drawing.getEventId());
        return drawing;
    }
}
