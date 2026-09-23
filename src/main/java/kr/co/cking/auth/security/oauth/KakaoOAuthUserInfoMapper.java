package kr.co.cking.auth.security.oauth;

import java.util.Map;
import kr.co.cking.auth.application.model.OAuthUserInfo;
import kr.co.cking.auth.domain.OAuthProvider;
import org.springframework.stereotype.Component;

/** Kakao UserInfo 응답의 최상위 {@code id}를 Cking 외부 Identity로 사용한다. */
@Component
public class KakaoOAuthUserInfoMapper implements OAuthUserInfoMapper {

    private static final String REGISTRATION_ID = "kakao";

    @Override
    public String registrationId() {
        return REGISTRATION_ID;
    }

    @Override
    public OAuthUserInfo map(Map<String, Object> attributes) {
        Map<String, Object> properties = OAuthUserInfoAttributes.optionalMap(attributes, "properties");
        Map<String, Object> kakaoAccount = OAuthUserInfoAttributes.optionalMap(attributes, "kakao_account");

        return new OAuthUserInfo(
                OAuthProvider.KAKAO,
                OAuthUserInfoAttributes.requiredPositiveIntegerIdentifier(attributes, "id"),
                OAuthUserInfoAttributes.optionalString(kakaoAccount, "email"),
                OAuthUserInfoAttributes.optionalString(properties, "nickname")
        );
    }
}
