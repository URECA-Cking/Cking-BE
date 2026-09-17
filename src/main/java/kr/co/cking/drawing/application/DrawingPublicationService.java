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
 * 검증된 INITIAL Drawing 공개를 처리하는 내부 Service 계약이다(FR-P2-044, 취합v1.5.4 §12).
 * 외부 HTTP API로 노출하지 않는다 — 외부 엔드포인트와 당첨자 Notification 생성(FR-P4-114)은
 * 호출자(시스템4의 PublicationService)가 자신의 Transaction 경계 안에서 담당한다
 * ({@code docs/domains/drawing/README.md}의 "책임 경계" 절 참고). 이 메서드가
 * {@code EventCommandService.publish()}까지 이미 호출하므로, 호출자는 이 메서드가 반환된
 * 뒤 Event 전이를 별도로 다시 호출하면 안 된다(재호출 시 두 번째 호출이 이미 PUBLISHED인
 * Event에 대해 {@code INVALID_STATE}로 실패해 호출자의 Transaction 전체가 Rollback된다).
 * 관리자 권한(adminId)은 호출자가 1차 검증하는 것을 전제로 하되, 도메인 경계를 넘는 호출이므로
 * 이 메서드도 {@link MemberQueryService#validateAdmin}으로 방어적으로 재검증한다. 반환값의
 * {@link DrawingPublicationResult#outcome}이 {@code PUBLISHED}일 때만 이번 호출에서 실제
 * 전이가 일어났다는 뜻이다 — 호출자는 이 값이 {@code PUBLISHED}일 때만 신규 Winner
 * Notification을 생성해야 한다(FR-P4-132·FR-P4-133). {@code ALREADY_PUBLISHED}(멱등
 * 재요청)에서도 매번 Notification 생성을 시도하면 DB unique 제약 위반으로 멱등 성공이어야
 * 할 호출이 예외로 실패할 수 있다.
 *
 * <p>공개는 Event.status가 {@code DRAW_COMPLETED}이고 공식 INITIAL Drawing이
 * {@code COMPLETED}+{@code PRIVATE}인 경우에만 허용하며, Drawing 공개와 Event
 * {@code DRAW_COMPLETED→PUBLISHED} 전이를 한 Tx로 묶어 둘 다 반영되거나 둘 다 반영되지
 * 않도록 한다. REDRAW 공개(FR-P4-115, Event가 이미 PUBLISHED인 경우)는 이번 구현 범위에서
 * 제외한다.
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
        if (drawing.getDrawType() != DrawingType.INITIAL) {
            throw new BusinessException(DrawingErrorCode.DRAWING_TYPE_NOT_SUPPORTED);
        }
        if (drawing.getStatus() != DrawingStatus.COMPLETED) {
            throw new BusinessException(DrawingErrorCode.DRAWING_NOT_COMPLETED);
        }

        EventDrawingSource event = eventDrawingQueryService.getDrawingSourceForUpdate(drawing.getEventId());

        if (drawing.getVisibility() == DrawingVisibility.PUBLIC) {
            if (event.status() != EventStatus.PUBLISHED) {
                throw new BusinessException(EventErrorCode.INVALID_STATE);
            }
            return toResult(drawing, PublicationOutcome.ALREADY_PUBLISHED);
        }

        if (event.status() != EventStatus.DRAW_COMPLETED) {
            throw new BusinessException(EventErrorCode.INVALID_STATE);
        }

        drawing.publish(clock.instant());
        eventCommandService.publish(drawing.getEventId());
        return toResult(drawing, PublicationOutcome.PUBLISHED);
    }

    private DrawingPublicationResult toResult(Drawing drawing, PublicationOutcome outcome) {
        return new DrawingPublicationResult(
                drawing.getId(),
                drawing.getEventId(),
                drawing.getVisibility(),
                drawing.getPublishedAt(),
                outcome
        );
    }
}
