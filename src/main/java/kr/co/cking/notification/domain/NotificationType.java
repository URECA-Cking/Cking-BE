package kr.co.cking.notification.domain;

/** 알림의 발생 유형을 구분한다. */
public enum NotificationType {
    COMMON_TICKET_REWARDED,
    CREATOR_TICKET_REWARDED,
    /** 최초 추첨 당첨. */
    INITIAL_WINNER,
    /** 재추첨 당첨. */
    REDRAW_WINNER,
    /** 최초 추첨 결과 공개 시 후보였으나 당첨되지 못한 미당첨자 결과 발표(이슈 #605). */
    INITIAL_NON_WINNER,
    /** 댓글 작성 제한 시작(관리자의 댓글 숨김 경고 또는 수동 제한, 이슈 #572). */
    COMMENT_RESTRICTION_STARTED,
    /** 댓글 작성 제한 해제(남은 제한이 없을 때, 이슈 #572). */
    COMMENT_RESTRICTION_RELEASED
}
