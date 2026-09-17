package kr.co.cking.drawing.application;

import java.time.Instant;
import kr.co.cking.drawing.domain.DrawingVisibility;

/** {@link DrawingPublicationService#publish}의 불변 결과다. 호출자가 영속 상태의 Drawing Entity에 직접 의존하지 않도록 한다. */
public record DrawingPublicationResult(
        Long drawingId,
        Long eventId,
        DrawingVisibility visibility,
        Instant publishedAt
) {
}
