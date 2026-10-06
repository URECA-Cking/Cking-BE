package kr.co.cking.abuse.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import kr.co.cking.abuse.application.AdminAbuseDetectionQueryService;
import kr.co.cking.abuse.application.model.AbuseDetectionSearchCondition;
import kr.co.cking.abuse.domain.AbuseDetection;
import kr.co.cking.abuse.domain.AbuseDetectionStatus;
import kr.co.cking.abuse.domain.AbuseTestFixtures;
import kr.co.cking.abuse.domain.AbuseType;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.security.WithMockJwt;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 관리자 Detection 목록 조회 Controller의 API 계약과 입력 검증을 확인한다. */
@WebMvcTest(AdminAbuseDetectionController.class)
@WithMockJwt(memberId = "1")
class AdminAbuseDetectionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminAbuseDetectionQueryService adminAbuseDetectionQueryService;

    /** 목록 조회는 조건을 Service에 전달하고 식별자와 Evidence 요약만 공통 응답에 담는다. */
    @Test
    void 목록을_조건별로_조회하고_식별자와_Evidence_요약을_반환한다() throws Exception {
        Instant detectedAt = Instant.parse("2026-10-02T00:30:00Z");
        AbuseDetection detection = AbuseDetection.restore(
                21L, 7L, AbuseType.FAILURE_BURST, AbuseDetectionStatus.DETECTED,
                detectedAt, null, null, AbuseTestFixtures.userEvidence());
        given(adminAbuseDetectionQueryService.list(eq(1L), any(AbuseDetectionSearchCondition.class), eq(2), eq(10)))
                .willReturn(new PageImpl<>(List.of(detection), PageRequest.of(2, 10), 21));

        mockMvc.perform(get("/api/admin/abuse-detections")
                        .param("memberId", "7")
                        .param("abuseType", "FAILURE_BURST")
                        .param("status", "DETECTED")
                        .param("detectedAtFrom", "2026-10-02T00:00:00Z")
                        .param("detectedAtTo", "2026-10-02T01:00:00Z")
                        .param("page", "2")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].detectionId").value(21))
                .andExpect(jsonPath("$.data.items[0].memberId").value(7))
                .andExpect(jsonPath("$.data.items[0].evidenceSummary.scope.type").value("USER"))
                .andExpect(jsonPath("$.data.items[0].evidence").doesNotExist())
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.totalElements").value(21))
                .andExpect(jsonPath("$.data.totalPages").value(3))
                .andExpect(jsonPath("$.data.hasNext").value(false));

        ArgumentCaptor<AbuseDetectionSearchCondition> condition = ArgumentCaptor.forClass(
                AbuseDetectionSearchCondition.class);
        then(adminAbuseDetectionQueryService).should().list(eq(1L), condition.capture(), eq(2), eq(10));
        org.assertj.core.api.Assertions.assertThat(condition.getValue())
                .isEqualTo(new AbuseDetectionSearchCondition(
                        7L,
                        AbuseType.FAILURE_BURST,
                        AbuseDetectionStatus.DETECTED,
                        Instant.parse("2026-10-02T00:00:00Z"),
                        Instant.parse("2026-10-02T01:00:00Z")));
    }

    /** 잘못된 필터·페이지·기간은 Service 호출 전에 공통 입력 검증 오류로 거절한다. */
    @Test
    void 잘못된_목록_조건은_검증_오류다() throws Exception {
        mockMvc.perform(get("/api/admin/abuse-detections").param("memberId", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/admin/abuse-detections").param("abuseType", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/admin/abuse-detections")
                        .param("detectedAtFrom", "2026-10-02T01:00:00Z")
                        .param("detectedAtTo", "2026-10-02T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/admin/abuse-detections").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(adminAbuseDetectionQueryService);
    }

    /** Application의 관리자 업무 권한 오류는 공통 FORBIDDEN 응답으로 반환한다. */
    @Test
    void 관리자_업무_권한_오류를_공통_응답으로_변환한다() throws Exception {
        given(adminAbuseDetectionQueryService.list(eq(1L), any(AbuseDetectionSearchCondition.class), eq(0), eq(20)))
                .willThrow(new BusinessException(CommonErrorCode.FORBIDDEN));

        mockMvc.perform(get("/api/admin/abuse-detections"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
