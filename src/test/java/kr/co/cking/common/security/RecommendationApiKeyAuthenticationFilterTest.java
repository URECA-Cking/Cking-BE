package kr.co.cking.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;

class RecommendationApiKeyAuthenticationFilterTest {

    private final AuthenticationEntryPoint entryPoint = mock(AuthenticationEntryPoint.class);

    @Test
    void 설정에_SHA_256이_아닌_값이_있으면_기동_시점에_실패한다() {
        assertThatThrownBy(() -> filter(List.of("not-hex")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> filter(List.of("abcd")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 같은_헤더가_여러_번_오면_유효한_키가_섞여_있어도_거부한다() throws Exception {
        // "key"의 SHA-256 (python3 -c 'import hashlib;print(hashlib.sha256(b"key").hexdigest())')
        var filter = filter(List.of("2c70e12b7a0646f92279f427c7b38e7334d8e5389cff167a1dc30e73f826b683"));
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/admin/creators/1/similar");
        request.addHeader("X-Cking-Recommendation-Key", "key");
        request.addHeader("X-Cking-Recommendation-Key", "key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        verify(entryPoint).commence(
                org.mockito.ArgumentMatchers.eq(request),
                org.mockito.ArgumentMatchers.eq(response),
                org.mockito.ArgumentMatchers.any(AuthenticationException.class));
        assertThat(chain.getRequest()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void 키_헤더가_있고_적재_엔드포인트일_때만_키_인증_요청으로_본다() {
        MockHttpServletRequest put = new MockHttpServletRequest("PUT", "/api/admin/creators/1/similar");
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/api/admin/creators/1/similar");
        put.addHeader("X-Cking-Recommendation-Key", "key");
        get.addHeader("X-Cking-Recommendation-Key", "key");

        assertThat(RecommendationApiKeyAuthenticationFilter.carriesApiKey(put)).isTrue();
        assertThat(RecommendationApiKeyAuthenticationFilter.carriesApiKey(get)).isFalse();
        assertThat(RecommendationApiKeyAuthenticationFilter.carriesApiKey(
                new MockHttpServletRequest("PUT", "/api/admin/creators/1/similar"))).isFalse();
    }

    private RecommendationApiKeyAuthenticationFilter filter(List<String> hashes) {
        return new RecommendationApiKeyAuthenticationFilter(hashes, entryPoint);
    }
}
