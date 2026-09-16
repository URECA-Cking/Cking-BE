package kr.co.cking.event.application.dto;

import java.util.UUID;

import kr.co.cking.event.domain.EntryResultCode;

/** 컨트롤러가 공통 응답 봉투의 {@code code}·응답 DTO 변환에 쓰는 원본 결과. */
public record EntryOutcome(EntryResultCode code, UUID requestId, Long eventId) {
}
