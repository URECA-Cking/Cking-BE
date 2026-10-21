package kr.co.cking.auth.presentation;

import static kr.co.cking.auth.presentation.RedisOAuth2AuthorizationRequestRepository.BINDING_COOKIE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;

class RedisOAuth2AuthorizationRequestRepositoryTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

    @Test
    void Redis_오류가_나면_콜백은_없는_인가_요청으로_처리한다() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.getAndDelete(anyString())).willThrow(new RedisConnectionFailureException("down"));
        given(valueOperations.get(anyString())).willThrow(new RedisConnectionFailureException("down"));
        RedisOAuth2AuthorizationRequestRepository repository =
                new RedisOAuth2AuthorizationRequestRepository(redisTemplate, true);

        assertThat(repository.removeAuthorizationRequest(callback(), new MockHttpServletResponse())).isNull();
        assertThat(repository.loadAuthorizationRequest(callback())).isNull();
    }

    @Test
    void state가_비어_있으면_저장하지_않는다() {
        RedisOAuth2AuthorizationRequestRepository repository =
                new RedisOAuth2AuthorizationRequestRepository(redisTemplate, true);
        OAuth2AuthorizationRequest withoutState = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .clientId("client")
                .build();

        assertThatThrownBy(() -> repository.saveAuthorizationRequest(
                withoutState, new MockHttpServletRequest(), new MockHttpServletResponse()))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void HTTPS_환경에서는_쿠키에_Secure를_붙인다() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        RedisOAuth2AuthorizationRequestRepository repository =
                new RedisOAuth2AuthorizationRequestRepository(redisTemplate, true);
        OAuth2AuthorizationRequest request = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .clientId("client")
                .state("state")
                .build();
        MockHttpServletResponse response = new MockHttpServletResponse();

        repository.saveAuthorizationRequest(request, new MockHttpServletRequest(), response);

        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
                .anySatisfy(cookie -> assertThat(cookie).startsWith(BINDING_COOKIE + "=").contains("Secure"));
    }

    private MockHttpServletRequest callback() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter(OAuth2ParameterNames.STATE, "state");
        request.setCookies(new Cookie(BINDING_COOKIE, "binding"));
        return request;
    }
}
