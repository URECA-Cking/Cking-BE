package kr.co.cking.subscriptionverification.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.subscriptionverification.application.CreatorYoutubeChannelService;
import kr.co.cking.subscriptionverification.application.YoutubeChannelConnectionService;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(YoutubeChannelConnectionController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = {
        "cking.youtube-oauth.frontend-result-url=https://dev.cking.co.kr/my-page",
        "cking.auth.refresh-cookie-secure=true"
})
class YoutubeChannelConnectionControllerTest {

    private static final String STATE = "A".repeat(43);
    private static final String COOKIE_NAME = "cking_youtube_connection_" + STATE;

    @Autowired private MockMvc mvc;
    @MockitoBean private YoutubeChannelConnectionService connections;
    @MockitoBean private CreatorYoutubeChannelService channels;

    @Test
    void 콜백_성공은_인가_코드_없이_정해진_FE_주소로_복귀한다() throws Exception {
        var result = mvc.perform(get("/login/oauth2/code/youtube")
                        .param("state", STATE).param("code", "secret-google-code")
                        .cookie(new Cookie(COOKIE_NAME, "binding")))
                .andExpect(status().is3xxRedirection()).andReturn();

        then(connections).should().complete(STATE, "binding", "secret-google-code");
        assertThat(result.getResponse().getRedirectedUrl())
                .isEqualTo("https://dev.cking.co.kr/my-page?youtubeConnection=success");
        assertThat(result.getResponse().getHeader("Set-Cookie"))
                .contains(COOKIE_NAME, "HttpOnly", "SameSite=Lax", "Path=/login/oauth2/code/youtube", "Max-Age=0");
    }

    @Test
    void 동의_취소는_상태를_소비하고_취소_결과만_전달한다() throws Exception {
        mvc.perform(get("/login/oauth2/code/youtube")
                        .param("state", STATE).param("error", "access_denied")
                        .cookie(new Cookie(COOKIE_NAME, "binding")))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl())
                        .isEqualTo("https://dev.cking.co.kr/my-page?youtubeConnection=cancelled"));
        then(connections).should().cancel(STATE, "binding");
    }

    @Test
    void 변조된_state는_민감한_값_없이_실패_결과로_복귀한다() throws Exception {
        willThrow(new BusinessException(SubscriptionVerificationErrorCode.CHANNEL_CONNECTION_INVALID))
                .given(connections).complete("invalid", null, "secret-google-code");

        mvc.perform(get("/login/oauth2/code/youtube")
                        .param("state", "invalid").param("code", "secret-google-code")
                        .cookie(new Cookie("cking_youtube_connection_" + "B".repeat(43), "binding")))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl())
                        .isEqualTo("https://dev.cking.co.kr/my-page?youtubeConnection=invalid"));
    }

    @Test
    void 서로_다른_연결_상태의_쿠키는_뒤_요청이_앞_요청을_덮어쓰지_않는다() throws Exception {
        mvc.perform(get("/login/oauth2/code/youtube")
                        .param("state", STATE).param("code", "code")
                        .cookie(new Cookie(COOKIE_NAME, "first"),
                                new Cookie("cking_youtube_connection_" + "B".repeat(43), "second")))
                .andExpect(status().is3xxRedirection());

        then(connections).should().complete(STATE, "first", "code");
    }

    @Test
    void 예기치_못한_처리_오류도_FE_결과_화면으로_복귀한다() throws Exception {
        willThrow(new IllegalStateException("database lock timeout"))
                .given(connections).complete(STATE, "binding", "code");

        mvc.perform(get("/login/oauth2/code/youtube")
                        .param("state", STATE).param("code", "code")
                        .cookie(new Cookie(COOKIE_NAME, "binding")))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl())
                        .isEqualTo("https://dev.cking.co.kr/my-page?youtubeConnection=unavailable"));
    }
}
