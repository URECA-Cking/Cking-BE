package kr.co.cking.post.presentation;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.post.application.AdminCommentReportCommandService;
import kr.co.cking.post.application.AdminCommentReportQueryService;
import kr.co.cking.post.application.dto.AdminCommentReportActionView;
import kr.co.cking.post.application.dto.AdminCommentReportView;
import kr.co.cking.post.domain.CommentReportReason;
import kr.co.cking.post.domain.CommentReportStatus;
import kr.co.cking.post.domain.PostErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminCommentReportController.class)
@WithMockJwt(memberId = "1")
class AdminCommentReportControllerTest {

    private static final String URL = "/api/admin/comment-reports";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminCommentReportQueryService queryService;

    @MockitoBean
    private AdminCommentReportCommandService commandService;

    @MockitoBean
    private MemberRepository memberRepository;

    @Test
    void 신고된_댓글_목록을_공통_페이지_형식으로_돌려주고_신고자를_담지_않는다() throws Exception {
        PageRequest pageable = PageRequest.of(1, 10);
        given(queryService.findReportedComments(1L, null, pageable)).willReturn(new PageImpl<>(List.of(
                new AdminCommentReportView(500L, 100L, 1L, 20L, "신고된 댓글", true, false,
                        CommentReportStatus.PENDING, 3L, 2L,
                        Map.of(CommentReportReason.ABUSE, 2L, CommentReportReason.SPAM, 1L),
                        Instant.parse("2026-10-07T00:00:00Z"))), pageable, 11));

        mockMvc.perform(get(URL).param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].commentId").value(500))
                .andExpect(jsonPath("$.data.items[0].creatorId").value(1))
                .andExpect(jsonPath("$.data.items[0].content").value("신고된 댓글"))
                .andExpect(jsonPath("$.data.items[0].blocked").value(true))
                .andExpect(jsonPath("$.data.items[0].hiddenByAdmin").value(false))
                .andExpect(jsonPath("$.data.items[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data.items[0].reportCount").value(3))
                .andExpect(jsonPath("$.data.items[0].pendingReportCount").value(2))
                .andExpect(jsonPath("$.data.items[0].reasonCounts.ABUSE").value(2))
                .andExpect(jsonPath("$.data.items[0].reasonCounts.SPAM").value(1))
                .andExpect(jsonPath("$.data.items[0].reporterMemberId").doesNotExist())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.totalElements").value(11));
    }

    @Test
    void 페이지_기본값은_0과_20이다() throws Exception {
        given(queryService.findReportedComments(1L, null, PageRequest.of(0, 20)))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void 페이지_크기가_범위를_벗어나면_400이다() throws Exception {
        mockMvc.perform(get(URL).param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get(URL).param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 관리자가_아니면_403이다() throws Exception {
        given(queryService.findReportedComments(1L, null, PageRequest.of(0, 20)))
                .willThrow(new BusinessException(CommonErrorCode.FORBIDDEN));

        mockMvc.perform(get(URL))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 상태로_거를_수_있다() throws Exception {
        given(queryService.findReportedComments(1L, CommentReportStatus.RESOLVED, PageRequest.of(0, 20)))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get(URL).param("status", "RESOLVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void 알_수_없는_상태_값은_400이다() throws Exception {
        mockMvc.perform(get(URL).param("status", "DONE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 숨김_조치는_처리한_신고_수와_함께_RESOLVED를_돌려준다() throws Exception {
        given(commandService.hide(1L, 500L))
                .willReturn(new AdminCommentReportActionView(500L, CommentReportStatus.RESOLVED, 3));

        mockMvc.perform(post(URL + "/500/hide"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.commentId").value(500))
                .andExpect(jsonPath("$.data.status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.handledReportCount").value(3));
    }

    @Test
    void 반려_조치는_REJECTED를_돌려준다() throws Exception {
        given(commandService.reject(1L, 500L))
                .willReturn(new AdminCommentReportActionView(500L, CommentReportStatus.REJECTED, 2));

        mockMvc.perform(post(URL + "/500/reject"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.handledReportCount").value(2));
    }

    @Test
    void 이미_다른_조치로_처리된_신고는_409다() throws Exception {
        given(commandService.reject(1L, 500L))
                .willThrow(new BusinessException(PostErrorCode.COMMENT_REPORT_ALREADY_HANDLED));

        mockMvc.perform(post(URL + "/500/reject"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMENT_REPORT_ALREADY_HANDLED"));
    }

    @Test
    void 신고가_없는_댓글의_조치는_404이고_관리자가_아니면_403이다() throws Exception {
        given(commandService.hide(1L, 404L)).willThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        given(commandService.hide(1L, 403L)).willThrow(new BusinessException(CommonErrorCode.FORBIDDEN));

        mockMvc.perform(post(URL + "/404/hide")).andExpect(status().isNotFound());
        mockMvc.perform(post(URL + "/403/hide")).andExpect(status().isForbidden());
    }
}
