package kr.co.cking.quiz.judge;

import kr.co.cking.quiz.generation.QuizProviderException;

/**
 * 실행 thread가 중단돼 Judge를 끝내지 못했다. 시간 초과와 구분하며, 결과를 확정할 수 없으므로 Worker는 UNKNOWN으로
 * 분류한다. 중단을 확인한 뒤에는 HTTP 요청을 더 보내지 않고, thread의 interrupt 상태는 유지한 채 던진다.
 *
 * <p>메시지는 고정 문구이고 cause를 붙이지 않는다. 중단 전까지 보낸 요청과 관측한 사용량은 {@link #getExecution()}에
 * 남긴다.
 */
public class QuizJudgeInterruptedException extends QuizProviderException {

    private final Phase phase;
    private final QuizJudgeExecution execution;

    public QuizJudgeInterruptedException(Phase phase, QuizJudgeExecution execution) {
        super("Quiz Judge 실행이 중단되었습니다.");
        this.phase = phase;
        this.execution = execution;
    }

    public Phase getPhase() {
        return phase;
    }

    public QuizJudgeExecution getExecution() {
        return execution;
    }

    /** 중단을 확인한 시점. 어느 쪽이든 결과는 확정할 수 없다. */
    public enum Phase {
        /** HTTP 요청을 보내기 직전이나 요청 중, 또는 응답을 받은 직후. 요청이 Provider에서 처리됐을 수 있다. */
        HTTP_REQUEST,
        /** 429·5xx 뒤 재시도를 기다리는 중. 직전 요청은 실패로 끝났고 다음 요청은 보내지 않았다. */
        RETRY_WAIT
    }
}
