package kr.co.cking.member.domain;

import kr.co.cking.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** Member 공개 프로필·닉네임 정책 오류다. */
@RequiredArgsConstructor
public enum MemberErrorCode implements ErrorCode {
    NICKNAME_ALREADY_TAKEN(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."),
    NICKNAME_CHANGE_TOO_SOON(HttpStatus.CONFLICT, "닉네임은 마지막 변경 후 14일이 지나야 바꿀 수 있습니다."),
    INVALID_PROFILE_IMAGE(HttpStatus.BAD_REQUEST, "프로필 이미지가 유효하지 않습니다."),
    PROFILE_IMAGE_UNAVAILABLE(HttpStatus.BAD_REQUEST, "사용할 수 없는 프로필 이미지입니다."),
    CREATOR_PROFILE_MANAGED_IN_SPACE(HttpStatus.CONFLICT, "크리에이터 공개 프로필은 스페이스 편집에서 관리해 주세요."),
    PROFILE_SETUP_REQUIRED(HttpStatus.CONFLICT, "닉네임 설정을 먼저 완료해 주세요.");

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
