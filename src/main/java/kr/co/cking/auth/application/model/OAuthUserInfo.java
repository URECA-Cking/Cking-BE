package kr.co.cking.auth.application.model;

import kr.co.cking.auth.domain.OAuthProvider;

/** Provider Mapper가 정규화한 외부 OAuth 사용자 정보. */
public record OAuthUserInfo(
        OAuthProvider provider,
        String providerUserId,
        String email,
        String name
) {

    public OAuthUserInfo {
        if (provider == null) {
            throw new IllegalArgumentException("OAuth Provider는 필수입니다.");
        }
        if (providerUserId == null || providerUserId.isBlank()) {
            throw new IllegalArgumentException("providerUserId는 필수입니다.");
        }
        if (providerUserId.length() > 255) {
            throw new IllegalArgumentException("providerUserId는 255자 이하여야 합니다.");
        }
    }
}
