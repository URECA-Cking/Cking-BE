package kr.co.cking.quiz.domain;

import kr.co.cking.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@RequiredArgsConstructor
public enum QuizErrorCode implements ErrorCode {
    QUIZ_NOT_FOUND(HttpStatus.NOT_FOUND, "Quiz를 찾을 수 없습니다."),
    QUIZ_REVISION_NOT_FOUND(HttpStatus.NOT_FOUND, "Quiz revision을 찾을 수 없습니다."),
    QUIZ_REVISION_ALREADY_DECIDED(HttpStatus.CONFLICT, "이미 다른 결정이 기록된 revision입니다."),
    QUIZ_JUDGE_EVALUATION_REQUIRED(HttpStatus.CONFLICT, "Judge 평가 검증을 통과한 revision만 승인·게시할 수 있습니다."),
    QUIZ_REVISION_NOT_APPROVED(HttpStatus.CONFLICT, "승인된 revision만 게시할 수 있습니다."),
    QUIZ_REVISION_NOT_LATEST(HttpStatus.CONFLICT, "최신 revision만 게시할 수 있습니다."),
    QUIZ_REVISION_INTEGRITY_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Quiz 데이터를 확인할 수 없습니다."),
    /** 내부 생성 결과 저장에서만 쓴다. 외부 API로 반환하지 않는다. */
    QUIZ_DRAFT_INVALID(HttpStatus.BAD_REQUEST, "저장할 수 없는 Quiz 생성 결과입니다."),
    QUIZ_GENERATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Quiz 생성 작업을 찾을 수 없습니다."),
    QUIZ_GENERATION_REQUEST_CONFLICT(HttpStatus.CONFLICT, "같은 requestId로 다른 내용의 생성 요청이 이미 있습니다."),
    QUIZ_GENERATION_IN_PROGRESS(HttpStatus.CONFLICT, "진행 중인 Quiz 생성 작업이 있습니다."),
    QUIZ_GENERATION_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "지금은 Quiz를 생성할 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String code() {
        return name();
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String message() {
        return message;
    }
}
