package kr.co.cking.auth.presentation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

/** 로그인 뒤 제공자 토큰을 보관하지 않는다. 앱은 로그인 뒤 자체 JWT만 쓰고 제공자 API를 다시 부르지 않는다. */
public class NoOpOAuth2AuthorizedClientRepository implements OAuth2AuthorizedClientRepository {

    @Override
    public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
            String clientRegistrationId, Authentication principal, HttpServletRequest request) {
        return null;
    }

    @Override
    public void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient, Authentication principal,
            HttpServletRequest request, HttpServletResponse response) {
    }

    @Override
    public void removeAuthorizedClient(String clientRegistrationId, Authentication principal,
            HttpServletRequest request, HttpServletResponse response) {
    }
}
