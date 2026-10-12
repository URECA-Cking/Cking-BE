package kr.co.cking.quiz.judge;

import java.util.Collection;

/**
 * 모든 문항·항목 판정의 요약. fail이 하나라도 있으면 {@link #HAS_FAIL}, fail 없이 uncertain이 있으면
 * {@link #UNCERTAIN_ONLY}, 모두 pass면 {@link #ALL_PASS}다. 요약은 승인 결정이 아니며 Creator 승인은 따로 필요하다.
 * 규칙을 바꾸면 {@link QuizJudgePrompt#POLICY_VERSION}을 올린다.
 */
public enum JudgeAggregation {

    ALL_PASS,
    HAS_FAIL,
    UNCERTAIN_ONLY;

    public static JudgeAggregation of(Collection<JudgeVerdict> verdicts) {
        if (verdicts.isEmpty()) {
            throw new IllegalArgumentException("판정이 없는 평가는 요약할 수 없습니다.");
        }
        if (verdicts.contains(JudgeVerdict.FAIL)) {
            return HAS_FAIL;
        }
        return verdicts.contains(JudgeVerdict.UNCERTAIN) ? UNCERTAIN_ONLY : ALL_PASS;
    }
}
