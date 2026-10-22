package kr.co.cking.follow.presentation;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.follow.application.CreatorFollowQueryService;
import kr.co.cking.follow.application.CreatorFollowService;
import kr.co.cking.follow.application.dto.FollowedCreatorView;
import kr.co.cking.follow.domain.FollowErrorCode;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CreatorFollowController.class)
class CreatorFollowControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreatorFollowService followService;

    @MockitoBean
    private CreatorFollowQueryService followQueryService;

    @MockitoBean
    private MemberRepository memberRepository;

    @Test
    @WithMockJwt(memberId = "7")
    void 팔로우는_인증된_사용자로_처리하고_팔로우_상태를_반환한다() throws Exception {
        mockMvc.perform(put("/api/creators/1/follow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.creatorId").value(1))
                .andExpect(jsonPath("$.data.following").value(true));

        then(followService).should().follow(7L, 1L);
    }

    @Test
    void 팔로우는_JWT가_없으면_401이다() throws Exception {
        mockMvc.perform(put("/api/creators/1/follow"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        then(followService).should(never()).follow(anyLong(), anyLong());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 본인_크리에이터_팔로우는_400이다() throws Exception {
        willThrow(new BusinessException(FollowErrorCode.SELF_FOLLOW_NOT_ALLOWED))
                .given(followService).follow(7L, 1L);

        mockMvc.perform(put("/api/creators/1/follow"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SELF_FOLLOW_NOT_ALLOWED"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 존재하지_않는_크리에이터_팔로우는_404다() throws Exception {
        willThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND))
                .given(followService).follow(7L, 999L);

        mockMvc.perform(put("/api/creators/999/follow"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 양수가_아닌_creatorId는_400이다() throws Exception {
        mockMvc.perform(put("/api/creators/0/follow"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 언팔로우는_팔로우하지_않은_상태를_반환한다() throws Exception {
        mockMvc.perform(delete("/api/creators/1/follow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.creatorId").value(1))
                .andExpect(jsonPath("$.data.following").value(false));

        then(followService).should().unfollow(7L, 1L);
    }

    @Test
    void 언팔로우는_JWT가_없으면_401이다() throws Exception {
        mockMvc.perform(delete("/api/creators/1/follow"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 팔로우_여부를_조회한다() throws Exception {
        given(followQueryService.findFollowStatus(7L, 1L)).willReturn(true);

        mockMvc.perform(get("/api/creators/1/follow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.creatorId").value(1))
                .andExpect(jsonPath("$.data.following").value(true));
    }

    @Test
    void 팔로우_여부_조회는_JWT가_없으면_401이다() throws Exception {
        mockMvc.perform(get("/api/creators/1/follow"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 내_팔로우_목록을_공통_페이지_형식으로_반환한다() throws Exception {
        PageRequest pageable = PageRequest.of(0, 20);
        given(followQueryService.findMine(7L, pageable)).willReturn(new PageImpl<>(
                List.of(new FollowedCreatorView(1L, "크리에이터", Instant.parse("2026-09-29T01:00:00Z"))),
                pageable, 1));

        mockMvc.perform(get("/api/me/follows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].creatorId").value(1))
                .andExpect(jsonPath("$.data.items[0].creatorName").value("크리에이터"))
                .andExpect(jsonPath("$.data.items[0].followedAt").value("2026-09-29T01:00:00Z"))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 내_팔로우_목록의_size는_100을_넘을_수_없다() throws Exception {
        mockMvc.perform(get("/api/me/follows").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 내_팔로우_목록은_JWT가_없으면_401이다() throws Exception {
        mockMvc.perform(get("/api/me/follows"))
                .andExpect(status().isUnauthorized());
    }
}
