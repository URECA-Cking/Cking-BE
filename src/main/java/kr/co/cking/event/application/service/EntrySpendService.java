package kr.co.cking.event.application.service;

import kr.co.cking.event.application.dto.EntrySpendResult;

// 응모(Entry) 요청을 entry-spend.lua로 원자 처리한다 (FR-P2-027~036).
public interface EntrySpendService {

    EntrySpendResult spend(
            Long eventId,
            Long userId,
            Long creatorId,
            String requestId,
            int ticketCount,
            String fingerprint
    );
}
