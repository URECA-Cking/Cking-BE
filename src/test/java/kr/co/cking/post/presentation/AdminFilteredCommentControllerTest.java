package kr.co.cking.post.presentation;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.post.application.AdminFilteredCommentQueryService;
import kr.co.cking.post.application.dto.AdminFilteredCommentView;
import kr.co.cking.post.domain.CommentFilterStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminFilteredCommentController.class)
@WithMockJwt(memberId = "1")
class AdminFilteredCommentControllerTest {

    private static final String URL = "/api/admin/filtered-comments";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminFilteredCommentQueryService queryService;

    @MockitoBean
    private MemberRepository memberRepository;

    @Test
    void 차단_댓글_목록을_공통_페이지_형식으로_돌려주고_규칙_모델_버전은_담지_않는다() throws Exception {
        PageRequest pageable = PageRequest.of(1, 10);
        given(queryService.findFilteredComments(1L, pageable)).willReturn(new PageImpl<>(List.of(
                new AdminFilteredCommentView(500L, 100L, "게시글 본문", 1L, "활동명", 20L, "닉네임", "차단된 댓글",
                        List.of("spam:link"), CommentFilterStatus.DONE, Instant.parse("2026-11-05T00:00:00Z"))),
                pageable, 11));

        mockMvc.perform(get(URL).param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].commentId").value(500))
                .andExpect(jsonPath("$.data.items[0].postId").value(100))
                .andExpect(jsonPath("$.data.items[0].postPreview").value("게시글 본문"))
                .andExpect(jsonPath("$.data.items[0].creatorId").value(1))
                .andExpect(jsonPath("$.data.items[0].creatorName").value("활동명"))
                .andExpect(jsonPath("$.data.items[0].authorMemberId").value(20))
                .andExpect(jsonPath("$.data.items[0].authorName").value("닉네임"))
                .andExpect(jsonPath("$.data.items[0].content").value("차단된 댓글"))
                .andExpect(jsonPath("$.data.items[0].filterReasons[0]").value("spam:link"))
                .andExpect(jsonPath("$.data.items[0].filterStatus").value("DONE"))
                .andExpect(jsonPath("$.data.items[0].filteredAt").value("2026-11-05T00:00:00Z"))
                .andExpect(jsonPath("$.data.items[0].filterRuleVersion").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].filterModelVersion").doesNotExist())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.totalElements").value(11));
    }

    @Test
    void 페이지_기본값은_0과_20이다() throws Exception {
        given(queryService.findFilteredComments(1L, PageRequest.of(0, 20)))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void 페이지_범위를_벗어나면_400이다() throws Exception {
        mockMvc.perform(get(URL).param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get(URL).param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 관리자가_아니면_403이다() throws Exception {
        given(queryService.findFilteredComments(1L, PageRequest.of(0, 20)))
                .willThrow(new BusinessException(CommonErrorCode.FORBIDDEN));

        mockMvc.perform(get(URL))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
