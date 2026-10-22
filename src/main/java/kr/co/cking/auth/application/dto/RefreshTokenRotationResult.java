package kr.co.cking.auth.application.dto;

/** Refresh Token 회전이 성공했을 때 다음 인증 수단 발급에 필요한 값을 전달한다. */
public record RefreshTokenRotationResult(
        Long memberId,
        String refreshToken
) {
}
