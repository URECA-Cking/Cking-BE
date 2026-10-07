package kr.co.cking.post.filter;

/** 필터 서비스 호출 실패. 타임아웃, 연결 실패, 5xx, 해석할 수 없는 응답을 모두 포함한다. */
public class CommentFilterException extends RuntimeException {

    public CommentFilterException(String message) {
        super(message);
    }

    public CommentFilterException(String message, Throwable cause) {
        super(message, cause);
    }
}
