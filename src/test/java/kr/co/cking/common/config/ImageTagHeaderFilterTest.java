package kr.co.cking.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ImageTagHeaderFilterTest {

    @Test
    void 이미지_태그가_있으면_응답_헤더에_싣는다() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ImageTagHeaderFilter("20261002120000-abc1234")
                .doFilter(new MockHttpServletRequest(), response, new MockFilterChain());

        assertThat(response.getHeader(ImageTagHeaderFilter.HEADER)).isEqualTo("20261002120000-abc1234");
    }

    @Test
    void 이미지_태그가_없으면_헤더를_싣지_않는다() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ImageTagHeaderFilter("")
                .doFilter(new MockHttpServletRequest(), response, new MockFilterChain());

        assertThat(response.containsHeader(ImageTagHeaderFilter.HEADER)).isFalse();
    }
}
