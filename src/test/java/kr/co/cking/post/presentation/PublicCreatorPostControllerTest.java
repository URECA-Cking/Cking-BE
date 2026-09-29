package kr.co.cking.post.presentation;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.post.application.CreatorPostQueryService;
import kr.co.cking.post.application.dto.CreatorPostView;
import kr.co.cking.post.domain.PostErrorCode;
import kr.co.cking.post.domain.PostVisibility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PublicCreatorPostController.class)
class PublicCreatorPostControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-29T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreatorPostQueryService postQueryService;

    @MockitoBean
    private MemberRepository memberRepository;

    @Test
    void 비로그인도_목록을_조회하고_잠긴_게시글은_본문_없이_받는다() throws Exception {
        PageRequest pageable = PageRequest.of(0, 20);
        given(postQueryService.findByCreator(1L, null, pageable)).willReturn(new PageImpl<>(List.of(
                new CreatorPostView(101L, 1L, PostVisibility.FOLLOWERS, true, null, 2, List.of(), NOW, NOW)),
                pageable, 1));

        mockMvc.perform(get("/api/creators/1/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].postId").value(101))
                .andExpect(jsonPath("$.data.items[0].locked").value(true))
                .andExpect(jsonPath("$.data.items[0].content").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].imageCount").value(2))
                .andExpect(jsonPath("$.data.items[0].images").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 로그인했으면_조회자_ID를_전달한다() throws Exception {
        PageRequest pageable = PageRequest.of(0, 20);
        given(postQueryService.findByCreator(1L, 7L, pageable)).willReturn(new PageImpl<>(List.of(), pageable, 0));

        mockMvc.perform(get("/api/creators/1/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void 권한_없는_팔로워_공개_게시글_상세는_403이다() throws Exception {
        given(postQueryService.findDetail(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq(101L), isNull()))
                .willThrow(new BusinessException(PostErrorCode.POST_FOLLOWERS_ONLY));

        mockMvc.perform(get("/api/creators/1/posts/101"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("POST_FOLLOWERS_ONLY"));
    }

    @Test
    void 목록_size는_100을_넘을_수_없다() throws Exception {
        mockMvc.perform(get("/api/creators/1/posts").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
