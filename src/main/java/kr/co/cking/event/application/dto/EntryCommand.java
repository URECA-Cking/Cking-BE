package kr.co.cking.event.application.dto;

import java.util.UUID;

import kr.co.cking.ticket.domain.CouponType;

/**
 * Controller가 {@code EntryRequest}를 변환해 전달하는 응모 커맨드.
 * {@code couponType}은 이 시점엔 이미 기본값(CREATOR)이 확정된 상태다.
 */
public record EntryCommand(Long userId, UUID requestId, Integer ticketCount, CouponType couponType) {
}
