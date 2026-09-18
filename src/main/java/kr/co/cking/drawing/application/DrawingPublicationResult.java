package kr.co.cking.drawing.application;

import java.time.Instant;
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.drawing.domain.DrawingVisibility;

/** {@link DrawingPublicationService#publish}의 불변 결과다. 호출자가 영속 상태의 Drawing Entity에 직접 의존하지 않도록 한다. */
public record DrawingPublicationResult(
        Long drawingId,
        Long eventId,
        DrawingType drawingType,
        DrawingVisibility visibility,
        Instant publishedAt,
        PublicationOutcome outcome
) {

    /** 기존 INITIAL 공개 호출부가 Drawing 유형을 명시하지 않아도 되도록 기본 유형을 보완한다. */
    public DrawingPublicationResult(
            Long drawingId,
            Long eventId,
            DrawingVisibility visibility,
            Instant publishedAt,
            PublicationOutcome outcome
    ) {
        this(drawingId, eventId, DrawingType.INITIAL, visibility, publishedAt, outcome);
    }

    /**
     * 이번 호출에서 {@code PRIVATE → PUBLIC} 전이가 실제로 일어났는지 구분한다(FR-P4-132·
     * FR-P4-133). 호출자(시스템4)는 {@code PUBLISHED}일 때만 신규 Winner Notification을
     * 생성해야 한다 — {@code ALREADY_PUBLISHED}(멱등 재요청)에서도 매번 생성을 시도하면,
     * DB unique 제약(`uk_notification_winner_type`)이 최종 중복은 막아도 그 제약 위반
     * 예외가 멱등 성공이어야 할 호출 전체를 실패시킬 수 있다.
     */
    public enum PublicationOutcome {
        /** 이번 호출에서 Drawing이 PRIVATE에서 PUBLIC으로 새로 전이됐다. */
        PUBLISHED,
        /** 이미 PUBLIC이던 Drawing의 멱등 재요청이라 상태를 바꾸지 않았다. */
        ALREADY_PUBLISHED
    }
}
