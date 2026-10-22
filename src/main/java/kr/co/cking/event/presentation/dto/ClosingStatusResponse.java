package kr.co.cking.event.presentation.dto;

import kr.co.cking.event.domain.EventStatus;

/** 마감 조회 API가 공개하는 현재 마감 상태만 담는 응답이다. */
public record ClosingStatusResponse(EventStatus status) {

    /** 시스템2 조회 결과에서 상태만 추려 외부 응답으로 변환한다. */
    public static ClosingStatusResponse from(EventStatus status) {
        return new ClosingStatusResponse(status);
    }
}
