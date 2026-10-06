package kr.co.cking.interest.domain;

/** 관심 분야 선택 규칙의 서버 기준값이다. */
public final class InterestPolicy {

    /** 한 회원이 고를 수 있는 관심 분야의 최대 개수(0개도 허용한다). */
    public static final int MAX_SELECTION = 3;

    private InterestPolicy() {
    }
}
