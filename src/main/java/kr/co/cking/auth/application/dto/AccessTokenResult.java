package kr.co.cking.auth.application.dto;

/** Access JWT 발급 유스케이스가 Presentation에 전달하는 결과다. */
public record AccessTokenResult(
        String accessToken,
        String tokenType,
        long expiresIn
) {
}
