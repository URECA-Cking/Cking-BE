package kr.co.cking.auth.presentation;

import java.util.Map;

import kr.co.cking.auth.application.model.OAuthUserInfo;
import kr.co.cking.auth.domain.OAuthProvider;
import org.springframework.stereotype.Component;

/** Google OpenID Connect 사용자 속성을 공통 OAuthUserInfo로 정규화한다. */
@Component
public class GoogleOAuthUserInfoMapper implements OAuthUserInfoMapper {

    /** Google registration ID만 이 Mapper가 처리하도록 식별한다. */
    @Override
    public boolean supports(String registrationId) {
        return "google".equals(registrationId);
    }

    /** Google의 sub, email, name 속성을 공통 사용자 정보로 변환한다. */
    @Override
    public OAuthUserInfo resolve(Map<String, Object> attributes) {
        return new OAuthUserInfo(
                OAuthProvider.GOOGLE,
                requiredString(attributes.get("sub")),
                optionalString(attributes.get("email")),
                optionalString(attributes.get("name"))
        );
    }

    /** 필수 Google 속성을 문자열로 바꾸고 누락값은 로그인 처리 실패로 전환한다. */
    private String requiredString(Object value) {
        String stringValue = optionalString(value);
        if (stringValue == null || stringValue.isBlank()) {
            throw new IllegalArgumentException("필수 OAuth 사용자 속성이 없습니다.");
        }
        return stringValue;
    }

    /** 선택 Google 속성을 문자열로 바꾸며 누락값은 null로 유지한다. */
    private String optionalString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
