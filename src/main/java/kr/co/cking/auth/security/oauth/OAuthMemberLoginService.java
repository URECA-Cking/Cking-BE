package kr.co.cking.auth.security.oauth;

import java.util.Map;
import kr.co.cking.auth.application.OAuthLoginService;
import kr.co.cking.auth.application.model.OAuthUserInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** OAuth Security 경계에서 정규화된 사용자 정보를 Member 연결 Application Service로 전달한다. */
@Service
@RequiredArgsConstructor
public class OAuthMemberLoginService {

    private final OAuthUserInfoResolver oauthUserInfoResolver;
    private final OAuthLoginService oauthLoginService;

    public Long login(String registrationId, Map<String, Object> attributes) {
        OAuthUserInfo userInfo = oauthUserInfoResolver.resolve(registrationId, attributes);
        return oauthLoginService.login(userInfo);
    }
}
