package kr.co.cking.quiz.domain;

import kr.co.cking.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@RequiredArgsConstructor
public enum QuizErrorCode implements ErrorCode {
    QUIZ_CLOSED(HttpStatus.CONFLICT, "마감된 Quiz는 변경하거나 새로 응시할 수 없습니다."),
    QUIZ_CLOSE_NOT_ALLOWED(HttpStatus.CONFLICT, "게시한 Quiz만 마감할 수 있습니다."),
    QUIZ_REVISION_CONFLICT(HttpStatus.CONFLICT, "편집 기준 revision이 변경됐거나 해시가 다릅니다."),
    QUIZ_EDIT_REQUEST_CONFLICT(HttpStatus.CONFLICT, "같은 requestId로 다른 편집 요청이 있습니다."),
    QUIZ_ATTEMPT_NOT_FOUND(HttpStatus.NOT_FOUND, "응시 기록을 찾을 수 없습니다."),
    QUIZ_ALREADY_ATTEMPTED(HttpStatus.CONFLICT, "이미 응시한 Quiz입니다."),
    QUIZ_ATTEMPT_REQUEST_CONFLICT(HttpStatus.CONFLICT, "같은 requestId로 다른 응시 요청이 있습니다."),
    QUIZ_PUBLISHED_REVISION_CHANGED(HttpStatus.CONFLICT, "게시된 revision이 변경됐습니다."),
    QUIZ_NOT_FOUND(HttpStatus.NOT_FOUND, "Quiz를 찾을 수 없습니다."),
    QUIZ_REVISION_NOT_FOUND(HttpStatus.NOT_FOUND, "Quiz revision을 찾을 수 없습니다."),
    QUIZ_REVISION_ALREADY_DECIDED(HttpStatus.CONFLICT, "이미 다른 결정이 기록된 revision입니다."),
    QUIZ_JUDGE_EVALUATION_REQUIRED(HttpStatus.CONFLICT, "Judge 평가 검증을 통과한 revision만 승인·게시할 수 있습니다."),
    QUIZ_REVISION_NOT_APPROVED(HttpStatus.CONFLICT, "승인된 revision만 게시할 수 있습니다."),
    QUIZ_REVISION_NOT_LATEST(HttpStatus.CONFLICT, "최신 revision만 게시할 수 있습니다."),
    QUIZ_CONTENT_LOCKED(HttpStatus.CONFLICT, "게시한 적 있는 Quiz는 처음 게시한 revision만 다시 게시할 수 있습니다."),
    QUIZ_REVISION_INTEGRITY_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Quiz 데이터를 확인할 수 없습니다."),
    /** 내부 생성 결과 저장에서만 쓴다. 외부 API로 반환하지 않는다. */
    QUIZ_DRAFT_INVALID(HttpStatus.BAD_REQUEST, "저장할 수 없는 Quiz 생성 결과입니다."),
    QUIZ_GENERATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Quiz 생성 작업을 찾을 수 없습니다."),
    QUIZ_GENERATION_REQUEST_CONFLICT(HttpStatus.CONFLICT, "같은 requestId로 다른 내용의 생성 요청이 이미 있습니다."),
    QUIZ_GENERATION_IN_PROGRESS(HttpStatus.CONFLICT, "진행 중인 Quiz 생성 작업이 있습니다."),
    QUIZ_GENERATION_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "지금은 Quiz를 생성할 수 없습니다."),
    QUIZ_GENERATION_NOT_AWAITING_REVIEW(HttpStatus.CONFLICT, "원문 검토를 기다리는 생성 작업이 아닙니다."),
    QUIZ_GENERATION_CONTENT_CONFLICT(HttpStatus.CONFLICT, "이미 다른 원문으로 확정한 생성 작업입니다."),
    QUIZ_JUDGE_EVALUATION_NOT_FOUND(HttpStatus.NOT_FOUND, "평가 작업을 찾을 수 없습니다."),
    QUIZ_JUDGE_REQUEST_CONFLICT(HttpStatus.CONFLICT, "같은 requestId로 다른 평가 요청이 있습니다."),
    QUIZ_JUDGE_IN_PROGRESS(HttpStatus.CONFLICT, "진행 중인 평가가 있습니다."),
    QUIZ_JUDGE_ATTEMPT_LIMIT_REACHED(HttpStatus.CONFLICT, "평가 시도 한도에 도달했습니다."),
    QUIZ_JUDGE_EVALUATION_COMPLETED(HttpStatus.CONFLICT, "완료된 평가를 다시 실행할 수 없습니다."),
    QUIZ_JUDGE_REVISION_APPROVED(HttpStatus.CONFLICT, "승인된 revision은 다시 평가할 수 없습니다."),
    QUIZ_JUDGE_SETTINGS_MISMATCH(HttpStatus.CONFLICT, "평가 설정이 바뀌어 이 revision을 다시 평가할 수 없습니다."),
    QUIZ_JUDGE_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "지금은 평가를 요청할 수 없습니다."),
    QUIZ_JUDGE_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "지금은 평가를 실행할 수 없습니다."),
    QUIZ_JUDGE_EVALUATION_INTEGRITY_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "평가 데이터를 확인할 수 없습니다.");

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
