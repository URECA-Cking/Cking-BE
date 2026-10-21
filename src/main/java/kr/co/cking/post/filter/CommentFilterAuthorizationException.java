package kr.co.cking.post.filter;

/** 필터 서비스가 인증·권한을 거부했다(401·403). 서명 설정이나 호출 권한 문제라 요청 형식 오류와 따로 기록한다. */
public class CommentFilterAuthorizationException extends CommentFilterException {

    public CommentFilterAuthorizationException(String message, int httpStatus, Throwable cause) {
        super(message, CommentFilterFailureType.UNAUTHORIZED, httpStatus, cause);
    }
}
