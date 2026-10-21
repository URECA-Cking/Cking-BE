package kr.co.cking.post.filter;

/** 필터 서비스 호출이 실패한 종류. 로그에는 예외 대신 이 값과 HTTP 상태만 남긴다. */
public enum CommentFilterFailureType {
    /** 연결·읽기 시간이 초과됐다. */
    TIMEOUT,
    /** 연결하지 못했거나 응답을 받기 전에 끊겼다. */
    NETWORK,
    /** 필터 서비스가 5xx를 응답했다. */
    SERVER_ERROR,
    /** 필터 서비스가 요청을 거절했다(4xx). */
    REJECTED,
    /** 필터 서비스가 인증·권한을 거부했다(401·403). 서명 설정이나 호출 권한 문제다. */
    UNAUTHORIZED,
    /** 응답이 비어 있거나 해석할 수 없다. */
    RESPONSE_PARSE
}
