package kr.co.cking.sanction.domain;

import kr.co.cking.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@RequiredArgsConstructor
public enum SanctionErrorCode implements ErrorCode {
    COMMENT_RESTRICTED(HttpStatus.FORBIDDEN, "댓글 작성이 제한되었습니다."),
    /** 크리에이터가 자기 스페이스에서 건 차단. 다른 스페이스에서는 쓸 수 있다. */
    SPACE_COMMENT_BLOCKED(HttpStatus.FORBIDDEN, "이 스페이스에서 댓글 작성이 제한되었습니다."),
    /** 크리에이터가 자기 자신을 차단하려는 요청. */
    CANNOT_BLOCK_SELF(HttpStatus.BAD_REQUEST, "자기 자신은 차단할 수 없습니다."),
    /** 크리에이터가 관리자를 차단하려는 요청. */
    CANNOT_BLOCK_ADMIN(HttpStatus.BAD_REQUEST, "관리자는 차단할 수 없습니다."),
    /** 스페이스 주인(Creator) 본인을 자기 스페이스에서 차단하려는 요청. 관리자가 지정한 경우에도 거부한다. */
    CANNOT_BLOCK_SPACE_OWNER(HttpStatus.BAD_REQUEST, "스페이스 주인은 자기 스페이스에서 차단할 수 없습니다."),
    /** 관리자가 크리에이터에게 모든 스페이스 댓글 작성 제한을 걸려는 요청. 자기 스페이스에서도 쓸 수 없게 되므로 거부한다. */
    CANNOT_SANCTION_CREATOR(HttpStatus.BAD_REQUEST, "크리에이터에게는 모든 스페이스 댓글 작성 제한을 걸 수 없습니다.");

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
