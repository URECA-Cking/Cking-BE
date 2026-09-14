package kr.co.cking.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 공통 응답 봉투.
 *
 * <p>HTTP 상태만으로는 결과를 구분할 수 없어 본문에 {@code code}를 함께 싣는다.
 * 명세 5.4의 {@code DUPLICATE_REPLAY}처럼 실패가 아니면서 성공과도 구분해야 하는
 * 결과가 존재하기 때문이다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        String code,
        T data,
        String message
) {

    private static final String SUCCESS = "SUCCESS";

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(SUCCESS, data, null);
    }

    public static ApiResponse<Void> success() {
        return new ApiResponse<>(SUCCESS, null, null);
    }

    /**
     * 성공·실패로 나눌 수 없는 결과 코드를 그대로 싣는다.
     * 명세 5.4의 Lua 반환 코드처럼 정상 응답이지만 코드가 SUCCESS가 아닌 경우에 쓴다.
     */
    public static <T> ApiResponse<T> of(String code, T data) {
        return new ApiResponse<>(code, data, null);
    }

    public static ApiResponse<Void> error(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.code(), null, errorCode.message());
    }

    public static ApiResponse<Void> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.code(), null, message);
    }
}
