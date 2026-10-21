package kr.co.cking.post.filter;

/**
 * 필터 서비스 호출 실패. 타임아웃, 연결 실패, 5xx, 해석할 수 없는 응답을 모두 포함한다.
 *
 * <p>cause(예: {@code RestClientResponseException})의 메시지에는 필터 서비스의 응답 본문이 길이 제한 없이 담길 수 있고,
 * 필터가 요청 값을 되돌려 주면 댓글 원문이 들어 있을 수 있다. 그래서 로그에는 예외 객체를 넘기지 않고 {@link #type()}과
 * {@link #httpStatus()}만 남긴다.
 */
public class CommentFilterException extends RuntimeException {

    private final CommentFilterFailureType type;
    private final int httpStatus;

    public CommentFilterException(String message, CommentFilterFailureType type, int httpStatus, Throwable cause) {
        super(message, cause);
        this.type = type;
        this.httpStatus = httpStatus;
    }

    public CommentFilterException(String message, CommentFilterFailureType type) {
        this(message, type, 0, null);
    }

    public CommentFilterFailureType type() {
        return type;
    }

    /** 구독 인증의 Gemini 호출 로그와 같은 규약이다. 응답을 받으면 그 상태, 타임아웃은 408, 응답을 받지 못했으면 0이다. */
    public int httpStatus() {
        return httpStatus;
    }
}
