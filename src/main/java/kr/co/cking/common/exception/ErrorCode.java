package kr.co.cking.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 모듈별 에러 코드 enum이 구현한다.
 *
 * <p>에러 코드는 {@code common}에 모으지 않고 각 모듈이 자기 enum을 정의한다.
 * 한 파일에 전부 모으면 동시 작업 시 변경이 충돌한다.
 */
public interface ErrorCode {

    /** 응답 본문에 노출되는 코드 문자열. enum 상수명을 그대로 쓴다. */
    String code();

    /** 매핑할 HTTP 상태. */
    HttpStatus status();

    /** 사용자에게 표시할 메시지. */
    String message();
}
