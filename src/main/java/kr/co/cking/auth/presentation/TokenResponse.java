package kr.co.cking.auth.presentation;

import kr.co.cking.auth.application.dto.AccessTokenResult;

/** Access Token 교환에 성공했을 때 반환하는 공개 응답 형식이다. */
public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn
) {

    /** Application의 Access Token 발급 결과를 외부 API 응답으로 변환한다. */
    public static TokenResponse from(AccessTokenResult result) {
        return new TokenResponse(result.accessToken(), result.tokenType(), result.expiresIn());
    }
}
