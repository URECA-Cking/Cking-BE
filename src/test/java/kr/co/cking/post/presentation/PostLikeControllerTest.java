package kr.co.cking.post.presentation;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.post.application.PostLikeService;
import kr.co.cking.post.application.dto.PostLikeView;
import kr.co.cking.post.domain.PostErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PostLikeController.class)
class PostLikeControllerTest {

    private static final String LIKES = "/api/creators/1/posts/100/likes";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PostLikeService likeService;

    @MockitoBean
    private MemberRepository memberRepository;

    @Test
    @WithMockJwt(memberId = "7")
    void 좋아요는_인증된_회원으로_호출하고_현재_상태를_반환한다() throws Exception {
        given(likeService.like(7L, 1L, 100L)).willReturn(new PostLikeView(100L, true, 3, true));

        mockMvc.perform(post(LIKES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.postId").value(100))
                .andExpect(jsonPath("$.data.liked").value(true))
                .andExpect(jsonPath("$.data.likeCount").value(3))
                .andExpect(jsonPath("$.data.missionAwarded").value(true));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 취소는_현재_상태를_반환한다() throws Exception {
        given(likeService.unlike(7L, 1L, 100L)).willReturn(new PostLikeView(100L, false, 2, false));

        mockMvc.perform(delete(LIKES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(false))
                .andExpect(jsonPath("$.data.likeCount").value(2))
                .andExpect(jsonPath("$.data.missionAwarded").value(false));
    }

    @Test
    void 비로그인은_401이고_서비스를_호출하지_않는다() throws Exception {
        mockMvc.perform(post(LIKES)).andExpect(status().isUnauthorized());
        mockMvc.perform(delete(LIKES)).andExpect(status().isUnauthorized());

        then(likeService).should(never()).like(anyLong(), anyLong(), anyLong());
        then(likeService).should(never()).unlike(anyLong(), anyLong(), anyLong());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 볼_수_없는_팔로워_공개_게시글은_403이다() throws Exception {
        given(likeService.like(any(), any(), any())).willThrow(new BusinessException(PostErrorCode.POST_FOLLOWERS_ONLY));

        mockMvc.perform(post(LIKES))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("POST_FOLLOWERS_ONLY"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 경로_ID가_양수가_아니면_400이다() throws Exception {
        mockMvc.perform(post("/api/creators/0/posts/100/likes")).andExpect(status().isBadRequest());
    }
}
