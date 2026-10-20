package kr.co.cking.follow.presentation;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.follow.application.CreatorPostNewsService;
import kr.co.cking.follow.application.dto.CreatorPostNewsView;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CreatorPostNewsController.class)
class CreatorPostNewsControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private CreatorPostNewsService newsService;
    @MockitoBean private MemberRepository memberRepository;

    @Test
    @WithMockJwt(memberId = "7")
    void 읽음_기록은_JWT_회원으로_처리한다() throws Exception {
        given(newsService.markRead(7L, 1L)).willReturn(Instant.parse("2026-10-20T05:30:00Z"));

        mockMvc.perform(put("/api/creators/1/posts/read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.creatorId").value(1))
                .andExpect(jsonPath("$.data.lastReadAt").value("2026-10-20T05:30:00Z"));
        then(newsService).should().markRead(7L, 1L);
    }

    @Test
    void 읽음_기록과_조회는_JWT가_없으면_401이다() throws Exception {
        mockMvc.perform(put("/api/creators/1/posts/read"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/me/follows/post-news"))
                .andExpect(status().isUnauthorized());
        then(newsService).should(never()).findMine(7L);
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 잘못된_creatorId는_400이다() throws Exception {
        mockMvc.perform(put("/api/creators/0/posts/read"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 팔로우한_크리에이터의_새_글_여부를_한_응답으로_받는다() throws Exception {
        given(newsService.findMine(7L)).willReturn(List.of(
                new CreatorPostNewsView(1L, true, Instant.parse("2026-10-20T05:20:00Z")),
                new CreatorPostNewsView(2L, false, null)));

        mockMvc.perform(get("/api/me/follows/post-news"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].creatorId").value(1))
                .andExpect(jsonPath("$.data.items[0].hasUnreadPosts").value(true))
                .andExpect(jsonPath("$.data.items[1].latestPostCreatedAt").value(nullValue()));
    }
}
