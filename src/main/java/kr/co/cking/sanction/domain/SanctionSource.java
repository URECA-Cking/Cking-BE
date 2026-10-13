package kr.co.cking.sanction.domain;

/** 제재가 만들어진 경로. */
public enum SanctionSource {
    /** 관리자의 댓글 숨김으로 자동 생성된 경고 겸 제한. 경고 횟수에 센다. */
    AUTOMATIC,
    /** 관리자가 직접 건 제한. 경고 횟수에 세지 않는다. */
    MANUAL
}
