package kr.co.cking.auth.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import kr.co.cking.auth.application.OAuthLoginService;
import kr.co.cking.auth.application.model.OAuthUserInfo;
import kr.co.cking.auth.domain.OAuthProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OAuthMemberLoginServiceTest {

    @Mock private OAuthLoginService oauthLoginService;

    @Test
    void resolvesProviderAttributesAndDelegatesToOAuthLoginService() {
        OAuthUserInfoResolver resolver = new OAuthUserInfoResolver(List.of(new GoogleOAuthUserInfoMapper()));
        OAuthMemberLoginService service = new OAuthMemberLoginService(resolver, oauthLoginService);
        OAuthUserInfo expected = new OAuthUserInfo(OAuthProvider.GOOGLE, "google-sub", "user@example.com", "사용자");
        when(oauthLoginService.login(expected)).thenReturn(101L);

        Long memberId = service.login("google", Map.of(
                "sub", "google-sub",
                "email", "user@example.com",
                "name", "사용자"
        ));

        assertThat(memberId).isEqualTo(101L);
        verify(oauthLoginService).login(expected);
    }
}
