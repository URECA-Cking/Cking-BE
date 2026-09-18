package kr.co.cking.drawing.presentation;

import java.time.Instant;

import kr.co.cking.drawing.application.DrawingPublicationResult;
import kr.co.cking.drawing.domain.DrawingVisibility;

/** 관리자에게 공개 완료된 Drawing의 현재 상태를 전달한다. */
public record DrawingPublicationResponse(
        Long drawingId,
        Long eventId,
        DrawingVisibility visibility,
        Instant publishedAt
) {

    /** 내부 공개 처리 결과를 외부 API 응답 형식으로 변환한다. */
    public static DrawingPublicationResponse from(DrawingPublicationResult result) {
        return new DrawingPublicationResponse(
                result.drawingId(),
                result.eventId(),
                result.visibility(),
                result.publishedAt()
        );
    }
}
