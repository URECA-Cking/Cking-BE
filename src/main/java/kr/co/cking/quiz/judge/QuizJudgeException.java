package kr.co.cking.quiz.judge;

import kr.co.cking.quiz.generation.QuizProviderException;

/**
 * 시간 초과를 제외한 Judge 실패. 재시도할 수 있는 오류는 Adapter 안에서 이미 재시도했으므로 호출자는 다시 호출하지
 * 않는다. fail·uncertain 판정은 실패가 아니라 정상 평가 결과다.
 *
 * <p>Provider 응답 원문·API key·평가 입력이 새지 않도록 메시지는 고정 문구만 쓰고 cause를 붙이지 않는다. 실패
 * 전까지 보낸 요청과 관측한 사용량은 {@link #getExecution()}에 남긴다.
 */
public class QuizJudgeException extends QuizProviderException {

    private final Failure failure;
    private final QuizJudgeExecution execution;

    public QuizJudgeException(Failure failure, QuizJudgeExecution execution) {
        super(failure.message);
        this.failure = failure;
        this.execution = execution;
    }

    public Failure getFailure() {
        return failure;
    }

    public QuizJudgeExecution getExecution() {
        return execution;
    }

    public enum Failure {
        /** API key가 설정되지 않아 호출하지 않았다. */
        NOT_CONFIGURED("Quiz Judge 설정이 없습니다."),
        /** Provider가 요청을 거부했다(429를 제외한 4xx). */
        REJECTED("Quiz Judge 요청이 거부되었습니다."),
        /**
         * 429·5xx 재시도를 소진했거나, Retry-After가 상한을 넘었거나, 남은 실행 시간이 부족해 재시도하지 않았거나,
         * 네트워크 오류가 났거나, 응답이 {@code failed} 상태다.
         */
        PROVIDER_ERROR("Quiz Judge 요청이 실패했습니다."),
        /** 모델이 평가를 거부했다. */
        REFUSAL("Quiz Judge가 평가를 거부했습니다."),
        /** 출력 token 한도 등으로 응답이 끝나지 않았다. 한도를 자동으로 늘리지 않는다. */
        INCOMPLETE("Quiz Judge 응답이 완료되지 않았습니다."),
        /** 응답이 형식·schema·문항 번호·reason 규칙을 지키지 않았다. */
        INVALID_RESPONSE("Quiz Judge 응답이 올바르지 않습니다.");

        private final String message;

        Failure(String message) {
            this.message = message;
        }
    }
}
