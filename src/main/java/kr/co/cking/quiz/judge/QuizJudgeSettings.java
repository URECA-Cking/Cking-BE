package kr.co.cking.quiz.judge;

import tools.jackson.databind.ObjectMapper;

/**
 * 평가 의미를 바꾸는 실행 설정 snapshot. 평가 결과와 함께 저장해, 이후 설정이 바뀌어도 그 평가가 어떤 설정으로
 * 만들어졌는지 알 수 있게 한다. 시각·requestId·API key·timeout·재시도처럼 평가 의미와 무관한 값은 넣지 않는다.
 *
 * @param maxOutputTokens   이 평가에 실제로 적용한 출력 token 한도(문항 수별)
 * @param instructionsSha256 reason 상한을 넣은 지시문의 SHA-256
 * @param schemaSha256      응답 schema JSON의 SHA-256
 */
public record QuizJudgeSettings(
        String model,
        String reasoningEffort,
        int maxOutputTokens,
        int reasonMaxLength,
        String promptVersion,
        String rubricVersion,
        String schemaVersion,
        String policyVersion,
        String instructionsSha256,
        String schemaSha256
) {

    /** 현재 버전의 prompt·schema로 snapshot을 만든다. */
    public static QuizJudgeSettings current(String model, String reasoningEffort, int maxOutputTokens,
                                            int reasonMaxLength, ObjectMapper objectMapper) {
        return new QuizJudgeSettings(model, reasoningEffort, maxOutputTokens, reasonMaxLength,
                QuizJudgePrompt.PROMPT_VERSION, QuizJudgePrompt.RUBRIC_VERSION, QuizJudgePrompt.SCHEMA_VERSION,
                QuizJudgePrompt.POLICY_VERSION,
                QuizJudgeFingerprint.sha256Hex(QuizJudgePrompt.instructions(reasonMaxLength)),
                QuizJudgeFingerprint.sha256Hex(objectMapper.writeValueAsString(QuizJudgePrompt.responseSchema())));
    }
}
