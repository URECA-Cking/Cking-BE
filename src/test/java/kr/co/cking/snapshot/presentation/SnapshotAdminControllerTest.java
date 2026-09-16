package kr.co.cking.snapshot.presentation;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.snapshot.application.SnapshotCandidateResult;
import kr.co.cking.snapshot.application.SnapshotQueryResult;
import kr.co.cking.snapshot.application.SnapshotQueryService;
import kr.co.cking.snapshot.domain.SnapshotErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SnapshotAdminController.class)
class SnapshotAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SnapshotQueryService snapshotQueryService;

    @Test
    void 관리자는_공통응답으로_Snapshot과_후보목록을_조회한다() throws Exception {
        SnapshotQueryResult result = new SnapshotQueryResult(
                20L,
                10L,
                2,
                "WEIGHTED",
                "WEIGHTED_V1",
                2,
                10L,
                "a".repeat(64),
                Instant.parse("2026-09-16T00:00:00Z"),
                List.of(
                        new SnapshotCandidateResult(1L, 3L),
                        new SnapshotCandidateResult(2L, 7L)
                )
        );
        when(snapshotQueryService.getOfficialSnapshot(10L, 30L)).thenReturn(result);

        mockMvc.perform(get("/api/admin/events/10/snapshot").param("userId", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.snapshotId").value(20))
                .andExpect(jsonPath("$.data.eventId").value(10))
                .andExpect(jsonPath("$.data.winnerCount").value(2))
                .andExpect(jsonPath("$.data.drawMethod").value("WEIGHTED"))
                .andExpect(jsonPath("$.data.algorithmVersion").value("WEIGHTED_V1"))
                .andExpect(jsonPath("$.data.candidateCount").value(2))
                .andExpect(jsonPath("$.data.totalTicketCount").value(10))
                .andExpect(jsonPath("$.data.snapshotHash").value("a".repeat(64)))
                .andExpect(jsonPath("$.data.createdAt").value("2026-09-16T00:00:00Z"))
                .andExpect(jsonPath("$.data.candidates[0].userId").value(1))
                .andExpect(jsonPath("$.data.candidates[0].ticketCount").value(3))
                .andExpect(jsonPath("$.data.candidates[1].userId").value(2))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void 존재하지_않는_관리자는_RESOURCE_NOT_FOUND를_반환한다() throws Exception {
        when(snapshotQueryService.getOfficialSnapshot(10L, 999L))
                .thenThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        mockMvc.perform(get("/api/admin/events/10/snapshot").param("userId", "999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void ADMIN이_아닌_사용자는_FORBIDDEN을_반환한다() throws Exception {
        when(snapshotQueryService.getOfficialSnapshot(10L, 30L))
                .thenThrow(new BusinessException(CommonErrorCode.FORBIDDEN));

        mockMvc.perform(get("/api/admin/events/10/snapshot").param("userId", "30"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 공식_Snapshot이_없으면_SNAPSHOT_NOT_FOUND를_반환한다() throws Exception {
        when(snapshotQueryService.getOfficialSnapshot(10L, 30L))
                .thenThrow(new BusinessException(SnapshotErrorCode.SNAPSHOT_NOT_FOUND));

        mockMvc.perform(get("/api/admin/events/10/snapshot").param("userId", "30"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SNAPSHOT_NOT_FOUND"));
    }

    @Test
    void userId가_없으면_VALIDATION_FAILED를_반환한다() throws Exception {
        mockMvc.perform(get("/api/admin/events/10/snapshot"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 식별자가_양수가_아니면_VALIDATION_FAILED를_반환한다() throws Exception {
        mockMvc.perform(get("/api/admin/events/0/snapshot").param("userId", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
