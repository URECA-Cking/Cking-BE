package kr.co.cking.event.application.service;

import kr.co.cking.event.application.dto.EntrySpendResult;
import kr.co.cking.ticket.domain.CouponType;

// 응모(Entry) 요청을 entry-spend.lua로 원자 처리한다 (FR-P2-027~036).
// fingerprint(FR-P2-029)는 요청 내용(eventId+userId+ticketCount[+couponType])으로부터
// 이 서비스가 직접 계산한다 - 호출부는 별도로 계산해 넘기지 않는다.
public interface EntrySpendService {

    EntrySpendResult spend(
            Long eventId,
            Long userId,
            Long creatorId,
            String requestId,
            int ticketCount,
            CouponType couponType
    );
}
