package kr.co.cking.auth.presentation;

import java.util.Map;

import kr.co.cking.auth.application.model.OAuthUserInfo;
import kr.co.cking.auth.domain.OAuthProvider;
import org.springframework.stereotype.Component;

/** Kakao 사용자 속성을 공통 OAuthUserInfo로 정규화한다. */
@Component
public class KakaoOAuthUserInfoMapper implements OAuthUserInfoMapper {

    /** Kakao registration ID만 이 Mapper가 처리하도록 식별한다. */
    @Override
    public boolean supports(String registrationId) {
        return "kakao".equals(registrationId);
    }

    /** Kakao의 id, 계정 이메일, 표준 프로필 닉네임을 공통 사용자 정보로 변환한다. */
    @Override
    public OAuthUserInfo resolve(Map<String, Object> attributes) {
        Map<String, Object> kakaoAccount = mapValue(attributes.get("kakao_account"));
        Map<String, Object> profile = mapValue(kakaoAccount.get("profile"));
        Map<String, Object> properties = mapValue(attributes.get("properties"));
        return new OAuthUserInfo(
                OAuthProvider.KAKAO,
                requiredString(attributes.get("id")),
                optionalString(kakaoAccount.get("email")),
                nickname(profile, properties)
        );
    }

    /** Kakao 표준 profile.nickname을 우선하고 레거시 properties.nickname은 호환용으로만 사용한다. */
    private String nickname(Map<String, Object> profile, Map<String, Object> properties) {
        String standardNickname = optionalString(profile.get("nickname"));
        return standardNickname != null ? standardNickname : optionalString(properties.get("nickname"));
    }

    /** 필수 Kakao 속성을 문자열로 바꾸고 누락값은 로그인 처리 실패로 전환한다. */
    private String requiredString(Object value) {
        String stringValue = optionalString(value);
        if (stringValue == null || stringValue.isBlank()) {
            throw new IllegalArgumentException("필수 OAuth 사용자 속성이 없습니다.");
        }
        return stringValue;
    }

    /** 선택 Kakao 속성을 문자열로 바꾸며 누락값은 null로 유지한다. */
    private String optionalString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 중첩 Kakao 속성이 Map이 아니거나 없으면 빈 Map으로 정규화한다. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }
}
