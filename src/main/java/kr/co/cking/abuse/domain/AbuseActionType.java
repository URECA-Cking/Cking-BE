package kr.co.cking.abuse.domain;

/** Abuse Detection v1이 관찰하는 사용자 명령 유형이다. */
public enum AbuseActionType {
    MISSION_COMPLETE,
    EVENT_ENTRY,
    /** YouTube 구독 인증 제출·판정 결과를 별도 정책으로 관찰한다. */
    YOUTUBE_SUBSCRIPTION
}
