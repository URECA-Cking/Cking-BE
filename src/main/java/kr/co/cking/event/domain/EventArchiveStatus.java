package kr.co.cking.event.domain;

/** 관리자 운영 목록에서 Event 보관 여부를 선택하는 필터다. */
public enum EventArchiveStatus {
    /** 사용자에게 노출되는 일반 Event만 조회한다. */
    ACTIVE,
    /** 관리자 보관함에만 보이는 Event만 조회한다. */
    ARCHIVED,
    /** 일반 Event와 보관 Event를 함께 조회한다. */
    ALL
}
