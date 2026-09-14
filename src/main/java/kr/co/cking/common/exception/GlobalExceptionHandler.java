package kr.co.cking.common.exception;

import kr.co.cking.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
