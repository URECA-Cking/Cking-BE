package kr.co.cking.quiz.judge;

import kr.co.cking.quiz.generation.QuizProviderTimeoutException;

/**
 * Judge 결과를 확인할 수 없는 시간 초과. Provider가 요청을 처리했을 수 있으므로 성공·실패를 추정하지 않고 자동으로
 * 다시 호출하지 않는다. 응답을 받았더라도 전체 실행 시간을 넘겼으면 그 결과는 쓰지 않고 이 예외로 끝낸다.
 *
 * <p>메시지는 고정 문구이고 cause를 붙이지 않는다.
 */
public class QuizJudgeTimeoutException extends QuizProviderTimeoutException {

    private final boolean executionDeadlineExceeded;
    private final QuizJudgeExecution execution;

    public QuizJudgeTimeoutException(boolean executionDeadlineExceeded, QuizJudgeExecution execution) {
        super(executionDeadlineExceeded ? "Quiz Judge 전체 실행 시간이 초과되었습니다." : "Quiz Judge 응답 시간이 초과되었습니다.",
                null);
        this.executionDeadlineExceeded = executionDeadlineExceeded;
        this.execution = execution;
    }

    /** true면 전체 실행 시간 초과, false면 HTTP 응답 대기 시간 초과다. */
    public boolean isExecutionDeadlineExceeded() {
        return executionDeadlineExceeded;
    }

    public QuizJudgeExecution getExecution() {
        return execution;
    }
}
