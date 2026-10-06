package kr.co.cking.post.filter;

/** 필터 서비스가 요청 자체를 거절했다(4xx). 요청 형식 불일치 같은 버그라 다시 보내도 같은 결과이므로 따로 구분해 기록한다. */
public class CommentFilterRejectedException extends CommentFilterException {

    public CommentFilterRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
