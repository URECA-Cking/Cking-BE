package kr.co.cking.post.presentation;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.post.application.CreatorPostCommentService;
import kr.co.cking.post.application.dto.CreatorPostCommentView;
import kr.co.cking.post.domain.PostErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CreatorPostCommentController.class)
class CreatorPostCommentControllerTest {

    private static final String BASE = "/api/creators/1/posts/100/comments";
    private static final Instant NOW = Instant.parse("2026-09-29T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreatorPostCommentService commentService;

    @MockitoBean
    private MemberRepository memberRepository;

    @Test
    void 비로그인도_댓글_목록을_공통_페이지_형식으로_조회한다() throws Exception {
        PageRequest pageable = PageRequest.of(0, 20);
        given(commentService.findByPost(1L, 100L, null, pageable))
                .willReturn(new PageImpl<>(List.of(view(7L, "응원해요")), pageable, 1));

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].commentId").value(500))
                .andExpect(jsonPath("$.data.items[0].authorName").value("팬"))
                .andExpect(jsonPath("$.data.items[0].writtenByCreator").value(false))
                .andExpect(jsonPath("$.data.items[0].content").value("응원해요"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 작성은_201과_댓글을_반환한다() throws Exception {
        given(commentService.create(7L, 1L, 100L, "응원해요")).willReturn(view(7L, "응원해요"));

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"content\": \"응원해요\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.commentId").value(500))
                .andExpect(jsonPath("$.data.authorMemberId").value(7));
    }

    @Test
    void 작성은_JWT가_없으면_401이다() throws Exception {
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"content\": \"응원해요\"}"))
                .andExpect(status().isUnauthorized());

        then(commentService).should(never()).create(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 팔로우하지_않으면_COMMENT_FOLLOWERS_ONLY다() throws Exception {
        given(commentService.create(7L, 1L, 100L, "응원해요"))
                .willThrow(new BusinessException(PostErrorCode.COMMENT_FOLLOWERS_ONLY));

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"content\": \"응원해요\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMENT_FOLLOWERS_ONLY"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 빈_댓글이나_500자를_넘는_댓글은_400이다() throws Exception {
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"content\": \" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"" + "a".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        then(commentService).should(never()).create(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 수정은_200과_댓글을_반환한다() throws Exception {
        given(commentService.update(7L, 1L, 100L, 500L, "수정")).willReturn(view(7L, "수정"));

        mockMvc.perform(patch(BASE + "/500").contentType(MediaType.APPLICATION_JSON).content("{\"content\": \"수정\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("수정"));
    }

    @Test
    void 수정은_JWT가_없으면_401이다() throws Exception {
        mockMvc.perform(patch(BASE + "/500").contentType(MediaType.APPLICATION_JSON).content("{\"content\": \"수정\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 삭제는_204다() throws Exception {
        mockMvc.perform(delete(BASE + "/500"))
                .andExpect(status().isNoContent());

        then(commentService).should().delete(7L, 1L, 100L, 500L);
    }

    @Test
    void 삭제는_JWT가_없으면_401이다() throws Exception {
        mockMvc.perform(delete(BASE + "/500"))
                .andExpect(status().isUnauthorized());
    }

    private CreatorPostCommentView view(Long authorMemberId, String content) {
        return new CreatorPostCommentView(500L, 100L, authorMemberId, "팬", false, content, false, false, NOW, NOW);
    }
}
