package kr.co.cking.subscriptionverification.domain;

/** 정규화 이미지가 최초 제출인지와 이전 제출자·Creator 관계를 표현한다. */
public enum SubscriptionVerificationImageReuseType {
    FIRST_USE,
    SAME_MEMBER_SAME_CREATOR,
    SAME_MEMBER_DIFFERENT_CREATOR,
    DIFFERENT_MEMBER
}
