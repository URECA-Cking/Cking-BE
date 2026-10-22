package kr.co.cking.abuse.domain;

/** Mission·Entry의 서로 다른 결과 코드를 탐지 관점으로 정규화한 분류다. */
public enum ResultClassification {
    NEW_SUCCESS,
    REPLAY,
    BUSINESS_FAILURE,
    SYSTEM_FAILURE
}
