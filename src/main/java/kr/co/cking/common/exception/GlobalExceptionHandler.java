package kr.co.cking.common.exception;

import kr.co.cking.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        // 업무 규칙 위반은 예상된 흐름이므로 스택트레이스를 남기지 않는다.
        log.warn("business exception: {} - {}", e.getErrorCode().code(), e.getMessage());
        return ResponseEntity
                .status(e.getErrorCode().status())
                .body(ApiResponse.error(e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(this::describe)
                .orElse(CommonErrorCode.INVALID_INPUT.message());

        return ResponseEntity
                .status(CommonErrorCode.INVALID_INPUT.status())
                .body(ApiResponse.error(CommonErrorCode.INVALID_INPUT, detail));
    }

    /**
     * 요청 자체가 잘못되어 컨트롤러에 닿기 전에 실패한 경우다.
     * catch-all로 흘려보내면 클라이언트 오류가 500으로 응답되고 스택트레이스까지 남는다.
     */
    @ExceptionHandler({
            HttpMessageNotReadableException.class,      // 본문 JSON 파싱 실패
            MethodArgumentTypeMismatchException.class,  // 경로·쿼리 파라미터 타입 불일치
            MissingServletRequestParameterException.class  // 필수 파라미터 누락
    })
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception e) {
        log.warn("bad request: {} - {}", e.getClass().getSimpleName(), e.getMessage());
        return ResponseEntity
                .status(CommonErrorCode.INVALID_INPUT.status())
                .body(ApiResponse.error(CommonErrorCode.INVALID_INPUT));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("method not allowed: {}", e.getMessage());
        return ResponseEntity
                .status(CommonErrorCode.METHOD_NOT_ALLOWED.status())
                .body(ApiResponse.error(CommonErrorCode.METHOD_NOT_ALLOWED));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        // 원인을 알 수 없는 예외만 스택트레이스를 남긴다.
        log.error("unhandled exception", e);
        return ResponseEntity
                .status(CommonErrorCode.INTERNAL_ERROR.status())
                .body(ApiResponse.error(CommonErrorCode.INTERNAL_ERROR));
    }

    private String describe(FieldError error) {
        return "%s: %s".formatted(error.getField(), error.getDefaultMessage());
    }
}
