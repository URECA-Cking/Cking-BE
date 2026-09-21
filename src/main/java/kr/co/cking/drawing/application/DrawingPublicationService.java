package kr.co.cking.drawing.application;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingErrorCode;
import kr.co.cking.drawing.domain.DrawingStatus;
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.drawing.domain.DrawingVisibility;
import kr.co.cking.drawing.application.DrawingPublicationResult.PublicationOutcome;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.event.application.EventDrawingQueryService;
import kr.co.cking.event.application.dto.EventDrawingSource;
import kr.co.cking.event.application.service.EventCommandService;
import kr.co.cking.event.domain.EventErrorCode;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.member.application.MemberQueryService;
import lombok.RequiredArgsConstructor;

/**
 * 검증된 Drawing을 공개하는 시스템3 Service다(FR-P2-044·FR-P4-115, 취합v1.5.4 §12). INITIAL은
 * 연결 Event를 {@code DRAW_COMPLETED → PUBLISHED}로 전이하고, REDRAW는 이미 공개된 Event를
 * {@code PUBLISHED}로 유지한다. 관리자 공개 API Controller는 시스템4
 * {@code PublicationService}에 {@code drawingId}, {@code userId}를 전달하고,
 * {@code PublicationService}가 이 Service를 호출한다. 이 Service는 관리자 권한과 공개 조건을
 * 검증한다.
 * INITIAL 처리에서 이 메서드가 {@code EventCommandService.publish()}까지 이미 호출하므로, 호출자는 반환 후 Event
 * 전이를 별도로 다시 호출하면 안 된다. REDRAW 처리에서는 이 메서드도 Event 전이 메서드를 호출하지 않는다.
 * INITIAL 처리 후 재호출하면 이미 {@code PUBLISHED}인 Event가
 * {@code INVALID_STATE}로 실패할 수 있다. 반환값의
 * {@link DrawingPublicationResult#outcome}이 {@code PUBLISHED}일 때만 이번 호출에서 실제
 * 전이가 일어났다는 뜻이다 — 호출자는 이 값이 {@code PUBLISHED}일 때만 신규 Winner
 * Notification을 생성해야 한다(FR-P4-132·FR-P4-133). {@code ALREADY_PUBLISHED}(멱등
 * 재요청)에서도 매번 Notification 생성을 시도하면 DB unique 제약 위반으로 멱등 성공이어야
 * 할 호출이 예외로 실패할 수 있다.
 *
 * <p>INITIAL 공개는 Event.status가 {@code DRAW_COMPLETED}이고 Drawing이
 * {@code COMPLETED}+{@code PRIVATE}인 경우에만 허용하며, Drawing 공개와 Event
 * {@code DRAW_COMPLETED→PUBLISHED} 전이를 한 Tx로 묶는다. REDRAW 공개는 Event가 이미
 * {@code PUBLISHED}이고 Drawing이 {@code COMPLETED}인 경우에만 허용하며 Drawing만 공개한다.
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
     * 처리). 이때도 Event가 실제로 PUBLISHED인지, Drawing.status가 실제로 COMPLETED인지 재확인해,
     * Drawing만 PUBLIC이고 Event는 PUBLISHED로 전환되지 않았거나 애초에 완료되지 않은 추첨인
     * 불일치 상태를 성공으로 위장하지 않는다.
     *
     * <p>Drawing과 Event 행을 모두 잠근 뒤 조회해 동시 공개 요청을 직렬화한다. Drawing만 잠그고
     * Event는 일반 조회로 읽으면, MySQL REPEATABLE READ의 트랜잭션 스냅샷 때문에 뒤에 도착한
     * 요청이 잠금 해제로 최신 Drawing(PUBLIC)은 보면서 Event는 자기 트랜잭션 시작 시점의 오래된
     * 스냅샷(PUBLISHED로 바뀌기 전)을 보는 불일치가 생겨 멱등 성공 대신 INVALID_STATE로 실패할
     * 수 있다.
     */
    public DrawingPublicationResult publish(Long drawingId, Long adminId) {
        memberQueryService.validateAdmin(adminId);

        Drawing drawing = drawingRepository.findByIdForPublish(drawingId)
                .orElseThrow(() -> new BusinessException(DrawingErrorCode.DRAWING_NOT_FOUND));
        if (drawing.getStatus() != DrawingStatus.COMPLETED) {
            throw new BusinessException(DrawingErrorCode.DRAWING_NOT_COMPLETED);
        }

        EventDrawingSource event = eventDrawingQueryService.getDrawingSourceForUpdate(drawing.getEventId());

        return switch (drawing.getDrawType()) {
            case INITIAL -> publishInitial(drawing, event.status());
            case REDRAW -> publishRedraw(drawing, event.status());
        };
    }

    /** INITIAL Drawing을 공개하고 연결 Event를 PUBLISHED로 전이한다. */
    private DrawingPublicationResult publishInitial(Drawing drawing, EventStatus eventStatus) {
        if (drawing.getVisibility() == DrawingVisibility.PUBLIC) {
            validatePublishedEvent(eventStatus);
            return toResult(drawing, PublicationOutcome.ALREADY_PUBLISHED);
        }
        if (eventStatus != EventStatus.DRAW_COMPLETED) {
            throw new BusinessException(EventErrorCode.INVALID_STATE);
        }
        drawing.publish(clock.instant());
        eventCommandService.publish(drawing.getEventId());
        return toResult(drawing, PublicationOutcome.PUBLISHED);
    }

    /** REDRAW Drawing을 공개하되 이미 PUBLISHED인 Event 상태는 변경하지 않는다. */
    private DrawingPublicationResult publishRedraw(Drawing drawing, EventStatus eventStatus) {
        validatePublishedEvent(eventStatus);
        if (drawing.getVisibility() == DrawingVisibility.PUBLIC) {
            return toResult(drawing, PublicationOutcome.ALREADY_PUBLISHED);
        }
        drawing.publish(clock.instant());
        return toResult(drawing, PublicationOutcome.PUBLISHED);
    }

    /** 공개 완료 또는 REDRAW 공개의 전제인 Event PUBLISHED 상태를 검증한다. */
    private void validatePublishedEvent(EventStatus eventStatus) {
        if (eventStatus != EventStatus.PUBLISHED) {
            throw new BusinessException(EventErrorCode.INVALID_STATE);
        }
    }

    /** 공개 처리 후 영속 Entity 대신 호출 경계를 위한 불변 결과를 만든다. */
    private DrawingPublicationResult toResult(Drawing drawing, PublicationOutcome outcome) {
        return new DrawingPublicationResult(
                drawing.getId(),
                drawing.getEventId(),
                drawing.getDrawType(),
                drawing.getVisibility(),
                drawing.getPublishedAt(),
                outcome
        );
    }
}
