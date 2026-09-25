package kr.co.cking.auth.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

@SpringBootTest(properties = {
        "OAUTH_GOOGLE_CLIENT_ID=test-google-client-id",
        "OAUTH_GOOGLE_CLIENT_SECRET=test-google-client-secret",
        "OAUTH_KAKAO_CLIENT_ID=test-kakao-rest-api-key",
        "OAUTH_KAKAO_CLIENT_SECRET=test-kakao-client-secret"
})
class OAuthClientRegistrationConfigurationTest {

    @Autowired private ClientRegistrationRepository clientRegistrationRepository;

    @Test
    void configuresGoogleAndKakaoClientRegistrations() {
        ClientRegistration google = clientRegistrationRepository.findByRegistrationId("google");
        ClientRegistration kakao = clientRegistrationRepository.findByRegistrationId("kakao");

        assertThat(google.getClientId()).isEqualTo("test-google-client-id");
        assertThat(google.getScopes()).contains("profile", "email");

        assertThat(kakao.getClientId()).isEqualTo("test-kakao-rest-api-key");
        assertThat(kakao.getClientAuthenticationMethod()).isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_POST);
        assertThat(kakao.getProviderDetails().getAuthorizationUri()).isEqualTo("https://kauth.kakao.com/oauth/authorize");
        assertThat(kakao.getProviderDetails().getTokenUri()).isEqualTo("https://kauth.kakao.com/oauth/token");
        assertThat(kakao.getProviderDetails().getUserInfoEndpoint().getUri())
                .isEqualTo("https://kapi.kakao.com/v2/user/me");
        assertThat(kakao.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName()).isEqualTo("id");
    }
}
