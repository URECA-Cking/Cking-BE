package kr.co.cking.quiz.judge;

/**
 * 완료된 평가 하나. 실행 상태가 성공이라는 뜻이며 판정이 pass라는 뜻은 아니다. 판정 요약은
 * {@code evaluation().aggregation()}이다.
 *
 * @param settings            이 평가에 적용한 설정 snapshot
 * @param inputSha256         {@link QuizJudgeFingerprint#inputSha256}
 * @param settingsFingerprint {@link QuizJudgeFingerprint#settingsFingerprint}
 * @param resultSha256        {@link QuizJudgeFingerprint#resultSha256}
 */
public record QuizJudgeResult(
        QuizJudgeEvaluation evaluation,
        QuizJudgeSettings settings,
        String inputSha256,
        String settingsFingerprint,
        String resultSha256,
        QuizJudgeExecution execution
) {
}
