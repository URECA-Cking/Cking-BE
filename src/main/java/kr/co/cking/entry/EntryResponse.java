package kr.co.cking.entry;

import java.util.UUID;

/**
 * SUCCESS/DUPLICATE_REPLAY 공통 성공 응답 스키마(통합 API 명세 v2.5 §5.3).
 * {@code entryId}는 포함하지 않는다 — 실제 응모 내역은 entries/me로 조회.
 */
public record EntryResponse(UUID requestId, Long eventId, boolean accepted) {

    public static EntryResponse accepted(UUID requestId, Long eventId) {
        return new EntryResponse(requestId, eventId, true);
    }
}
