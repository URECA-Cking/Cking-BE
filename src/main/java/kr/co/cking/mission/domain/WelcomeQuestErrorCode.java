package kr.co.cking.mission.domain;

import kr.co.cking.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 웰컴 퀘스트의 대상·조건·중복 지급 업무 오류다. */
@RequiredArgsConstructor
public enum WelcomeQuestErrorCode implements ErrorCode {
    WELCOME_QUEST_NOT_ELIGIBLE(HttpStatus.FORBIDDEN, "웰컴 퀘스트 대상 회원이 아닙니다."),
    WELCOME_QUEST_CONDITION_NOT_MET(HttpStatus.CONFLICT, "현재 팔로우 중인 크리에이터가 없습니다."),
    WELCOME_QUEST_WRONG_ANSWER(HttpStatus.CONFLICT, "튜토리얼 퀴즈의 정답이 아닙니다."),
    WELCOME_QUEST_ALREADY_CLAIMED(HttpStatus.CONFLICT, "이미 다른 요청으로 받은 보상입니다.");

    private final HttpStatus status;
    private final String message;

    /** 외부 응답에 사용할 업무 오류 코드 이름을 반환한다. */
    @Override
    public String code() { return name(); }

    /** 업무 오류의 HTTP 상태를 반환한다. */
    @Override
    public HttpStatus status() { return status; }

    /** 사용자에게 표시할 기본 오류 메시지를 반환한다. */
    @Override
    public String message() { return message; }
}
