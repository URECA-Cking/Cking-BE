package kr.co.cking.quiz.judge;

/**
 * HTTP 응답 하나에서 관측한 token 사용량. Provider가 주지 않은 값은 0이 아니라 null이다. reasoning token은 output
 * token에 이미 포함된 세부 값이므로 비용 계산에서 다시 더하지 않는다. cached input token도 input token에 포함된
 * 세부 값이다.
 */
public record JudgeUsage(
        Long inputTokens,
        Long cachedInputTokens,
        Long outputTokens,
        Long reasoningTokens,
        Long totalTokens
) {

    /**
     * 비용을 계산할 수 있는지. input·cached input·output token이 모두 있고 cached가 input을 넘지 않아야 한다. 빠진
     * cached 값을 0으로 보거나 모순된 값을 보정하지 않는다.
     */
    public boolean isCostComputable() {
        return inputTokens != null && cachedInputTokens != null && outputTokens != null
                && cachedInputTokens <= inputTokens;
    }
}
