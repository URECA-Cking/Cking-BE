package kr.co.cking.quiz.domain;

/**
 * 승인 결정이 따른 정책. 승인 행에만 있고 거절 행은 값이 없다.
 *
 * <p>{@link #LEGACY_JUDGE_V1}은 기존 승인으로, 저장된 Judge 결과(ALL_PASS)의 ID·해시를 근거로 고정한다.
 * {@link #CREATOR_REVIEW_V1}은 Creator가 최종 문항 집합을 명시적으로 책임 승인한 것으로, Judge 근거를 쓰지 않는다.
 */
public enum QuizApprovalPolicyType {
    LEGACY_JUDGE_V1,
    CREATOR_REVIEW_V1
}
