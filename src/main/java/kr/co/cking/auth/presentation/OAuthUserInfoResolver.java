package kr.co.cking.auth.presentation;

import java.util.Map;

import kr.co.cking.auth.application.model.OAuthUserInfo;
import kr.co.cking.auth.domain.OAuthProvider;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Component;

/** Provider별 OAuth2 사용자 속성을 Auth Application의 공통 모델로 정규화한다. */
@Component
public class OAuthUserInfoResolver {

    /** 인증 토큰의 registration ID에 맞춰 Google 또는 Kakao 사용자 정보를 정규화한다. */
    public OAuthUserInfo resolve(OAuth2AuthenticationToken authentication) {
        return switch (authentication.getAuthorizedClientRegistrationId()) {
            case "google" -> resolveGoogle(authentication.getPrincipal().getAttributes());
            case "kakao" -> resolveKakao(authentication.getPrincipal().getAttributes());
            default -> throw new IllegalArgumentException("지원하지 않는 OAuth Provider입니다.");
        };
    }

    /** Google의 표준 OpenID Connect 속성에서 외부 Identity와 프로필을 추출한다. */
    private OAuthUserInfo resolveGoogle(Map<String, Object> attributes) {
        return new OAuthUserInfo(
                OAuthProvider.GOOGLE,
                requiredString(attributes.get("sub")),
                optionalString(attributes.get("email")),
                optionalString(attributes.get("name"))
        );
    }

    /** Kakao 응답의 id, kakao_account, properties 구조를 공통 사용자 정보로 정규화한다. */
    private OAuthUserInfo resolveKakao(Map<String, Object> attributes) {
        Map<String, Object> kakaoAccount = mapValue(attributes.get("kakao_account"));
        Map<String, Object> properties = mapValue(attributes.get("properties"));
        return new OAuthUserInfo(
                OAuthProvider.KAKAO,
                requiredString(attributes.get("id")),
                optionalString(kakaoAccount.get("email")),
                optionalString(properties.get("nickname"))
        );
    }

    /** 필수 OAuth 속성을 문자열로 바꾸고 누락값은 로그인 처리 실패로 전환한다. */
    private String requiredString(Object value) {
        String stringValue = optionalString(value);
        if (stringValue == null || stringValue.isBlank()) {
            throw new IllegalArgumentException("필수 OAuth 사용자 속성이 없습니다.");
        }
        return stringValue;
    }

    /** 선택 OAuth 속성을 문자열로 바꾸며 누락값은 null로 유지한다. */
    private String optionalString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 중첩 Provider 속성이 Map이 아니거나 없으면 빈 Map으로 정규화한다. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }
}
