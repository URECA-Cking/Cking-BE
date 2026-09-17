package kr.co.cking.mission;

import tools.jackson.databind.ObjectMapper;
import kr.co.cking.mission.application.MissionCompletionService;
import kr.co.cking.mission.application.dto.MissionCompleteOutcome;
import kr.co.cking.mission.presentation.MissionController;
import kr.co.cking.mission.presentation.dto.MissionCompleteRequest;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MissionController.class)
class MissionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private MissionCompletionService missionCompletionService;

    @Test
    void 최초_완료는_202와_EARN_ACCEPTED를_반환한다() throws Exception {
        MissionCompleteRequest request = new MissionCompleteRequest(1L, UUID.randomUUID());
        MissionCompleteOutcome outcome = new MissionCompleteOutcome(
                EarnResultCode.EARN_ACCEPTED, 100L, 1, LocalDateTime.parse("2026-09-16T10:00:00"));
        when(missionCompletionService.complete(eq(10L), eq(100L), any())).thenReturn(outcome);

        mockMvc.perform(post("/api/creators/10/missions/100/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value("EARN_ACCEPTED"))
                .andExpect(jsonPath("$.data.missionId").value(100))
                .andExpect(jsonPath("$.data.rewardAmount").value(1));
    }

    @Test
    void 재요청은_200과_ALREADY_PROCESSED를_반환한다() throws Exception {
        MissionCompleteRequest request = new MissionCompleteRequest(1L, UUID.randomUUID());
        MissionCompleteOutcome outcome = new MissionCompleteOutcome(
                EarnResultCode.ALREADY_PROCESSED, 100L, 1, LocalDateTime.parse("2026-09-16T10:00:00"));
        when(missionCompletionService.complete(eq(10L), eq(100L), any())).thenReturn(outcome);

        mockMvc.perform(post("/api/creators/10/missions/100/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("ALREADY_PROCESSED"));
    }

    @Test
    void userId가_없으면_400과_VALIDATION_FAILED를_반환한다() throws Exception {
        mockMvc.perform(post("/api/creators/10/missions/100/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void requestId가_없으면_400과_VALIDATION_FAILED를_반환한다() throws Exception {
        mockMvc.perform(post("/api/creators/10/missions/100/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
