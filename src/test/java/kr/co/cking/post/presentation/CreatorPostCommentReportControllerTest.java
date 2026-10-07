package kr.co.cking.post.presentation;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.post.application.CreatorPostCommentReportService;
import kr.co.cking.post.application.dto.CommentReportView;
import kr.co.cking.post.domain.CommentReportReason;
import kr.co.cking.post.domain.PostErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CreatorPostCommentReportController.class)
class CreatorPostCommentReportControllerTest {

    private static final String URL = "/api/creators/1/posts/100/comments/500/reports";
    private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreatorPostCommentReportService reportService;

    @MockitoBean
    private MemberRepository memberRepository;

    @Test
    @WithMockJwt(memberId = "7")
    void 신고는_201과_접수된_신고를_반환하고_신고자를_담지_않는다() throws Exception {
        given(reportService.report(7L, 1L, 100L, 500L, CommentReportReason.ABUSE, null))
                .willReturn(new CommentReportView(900L, 500L, CommentReportReason.ABUSE, NOW));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"ABUSE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.reportId").value(900))
                .andExpect(jsonPath("$.data.commentId").value(500))
                .andExpect(jsonPath("$.data.reason").value("ABUSE"))
                .andExpect(jsonPath("$.data.reporterMemberId").doesNotExist());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 기타_사유의_설명을_Service에_전달한다() throws Exception {
        given(reportService.report(7L, 1L, 100L, 500L, CommentReportReason.OTHER, "위협으로 느껴집니다"))
                .willReturn(new CommentReportView(901L, 500L, CommentReportReason.OTHER, NOW));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"OTHER\", \"detail\": \"위협으로 느껴집니다\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.reason").value("OTHER"));
    }

    @Test
    void 신고는_JWT가_없으면_401이다() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"ABUSE\"}"))
                .andExpect(status().isUnauthorized());

        then(reportService).should(never()).report(anyLong(), anyLong(), anyLong(), anyLong(), any(), any());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 사유가_없거나_알_수_없거나_설명이_200자를_넘으면_400이다() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"UNKNOWN\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"OTHER\", \"detail\": \"" + "a".repeat(201) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        then(reportService).should(never()).report(anyLong(), anyLong(), anyLong(), anyLong(), any(), any());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 본인_댓글_신고는_403이다() throws Exception {
        given(reportService.report(7L, 1L, 100L, 500L, CommentReportReason.ABUSE, null))
                .willThrow(new BusinessException(PostErrorCode.COMMENT_REPORT_OWN_COMMENT));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"ABUSE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMENT_REPORT_OWN_COMMENT"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 반복_신고_한도를_넘으면_429다() throws Exception {
        given(reportService.report(7L, 1L, 100L, 500L, CommentReportReason.ABUSE, null))
                .willThrow(new BusinessException(PostErrorCode.COMMENT_REPORT_LIMIT_EXCEEDED));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"ABUSE\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("COMMENT_REPORT_LIMIT_EXCEEDED"));
    }
}
