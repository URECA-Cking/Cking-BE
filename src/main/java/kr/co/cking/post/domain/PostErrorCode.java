package kr.co.cking.post.domain;

import kr.co.cking.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@RequiredArgsConstructor
public enum PostErrorCode implements ErrorCode {
    INVALID_POST_IMAGE(HttpStatus.BAD_REQUEST, "게시글 이미지가 유효하지 않습니다."),
    POST_IMAGE_UNAVAILABLE(HttpStatus.BAD_REQUEST, "사용할 수 없는 이미지가 포함되어 있습니다."),
    POST_FOLLOWERS_ONLY(HttpStatus.FORBIDDEN, "팔로워만 볼 수 있는 게시글입니다."),
    COMMENT_FOLLOWERS_ONLY(HttpStatus.FORBIDDEN, "팔로워만 댓글을 작성할 수 있습니다."),
    COMMENT_NOT_REVEALABLE(HttpStatus.FORBIDDEN, "원문을 볼 수 없는 댓글입니다."),
    COMMENT_REPORT_OWN_COMMENT(HttpStatus.FORBIDDEN, "본인 댓글은 신고할 수 없습니다."),
    COMMENT_REPORT_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "신고를 너무 자주 하고 있습니다. 잠시 후 다시 시도해 주세요.");

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
