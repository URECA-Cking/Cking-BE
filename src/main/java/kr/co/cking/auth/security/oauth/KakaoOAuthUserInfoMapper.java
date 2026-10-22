package kr.co.cking.auth.security.oauth;

import java.util.Map;
import kr.co.cking.auth.application.model.OAuthUserInfo;
import kr.co.cking.auth.domain.OAuthProvider;
import org.springframework.stereotype.Component;

/** Kakao UserInfo 응답의 최상위 {@code id}를 Cking 외부 Identity로 사용한다. */
@Component
public class KakaoOAuthUserInfoMapper implements OAuthUserInfoMapper {

    private static final String REGISTRATION_ID = "kakao";

    /** Kakao OAuth Client registration ID를 반환한다. */
    @Override
    public String registrationId() {
        return REGISTRATION_ID;
    }

    /** Kakao 응답의 외부 Identity·선택 프로필을 공통 OAuthUserInfo로 정규화한다. */
    @Override
    public OAuthUserInfo map(Map<String, Object> attributes) {
        Map<String, Object> properties = OAuthUserInfoAttributes.optionalMap(attributes, "properties");
        Map<String, Object> kakaoAccount = OAuthUserInfoAttributes.optionalMap(attributes, "kakao_account");
        Map<String, Object> profile = OAuthUserInfoAttributes.optionalMap(kakaoAccount, "profile");

        return new OAuthUserInfo(
                OAuthProvider.KAKAO,
                OAuthUserInfoAttributes.requiredPositiveIntegerIdentifier(attributes, "id"),
                OAuthUserInfoAttributes.optionalString(kakaoAccount, "email"),
                nickname(profile, properties)
        );
    }

    /** Kakao 표준 profile.nickname을 우선하고 레거시 properties.nickname은 호환용으로만 사용한다. */
    private String nickname(Map<String, Object> profile, Map<String, Object> properties) {
        String standardNickname = OAuthUserInfoAttributes.optionalString(profile, "nickname");
        return standardNickname != null
                ? standardNickname
                : OAuthUserInfoAttributes.optionalString(properties, "nickname");
    }
}
