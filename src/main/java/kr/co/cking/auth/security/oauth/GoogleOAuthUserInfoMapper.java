package kr.co.cking.auth.security.oauth;

import java.util.Map;
import kr.co.cking.auth.application.model.OAuthUserInfo;
import kr.co.cking.auth.domain.OAuthProvider;
import org.springframework.stereotype.Component;

/** Google UserInfo 응답의 {@code sub}를 Cking 외부 Identity로 사용한다. */
@Component
public class GoogleOAuthUserInfoMapper implements OAuthUserInfoMapper {

    private static final String REGISTRATION_ID = "google";

    @Override
    public String registrationId() {
        return REGISTRATION_ID;
    }

    @Override
    public OAuthUserInfo map(Map<String, Object> attributes) {
        return new OAuthUserInfo(
                OAuthProvider.GOOGLE,
                OAuthUserInfoAttributes.requiredString(attributes, "sub"),
                OAuthUserInfoAttributes.optionalString(attributes, "email"),
                OAuthUserInfoAttributes.optionalString(attributes, "name")
        );
    }
}
