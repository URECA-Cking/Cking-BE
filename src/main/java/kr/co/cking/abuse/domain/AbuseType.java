package kr.co.cking.abuse.domain;

/** MySQL Detection으로 영속화하는 비정상 행동 유형이다. */
public enum AbuseType {
    MISSION_REQUEST_BURST,
    DUPLICATE_MISSION_BURST,
    ENTRY_REQUEST_BURST,
    INSUFFICIENT_BALANCE_BURST,
    RAPID_EARN_AND_SPEND,
    FAILURE_BURST
}
