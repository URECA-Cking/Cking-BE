package kr.co.cking.mission;

import tools.jackson.databind.ObjectMapper;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.mission.application.MissionCompletionService;
import kr.co.cking.mission.application.CreatorSpaceShareMissionCompletionService;
import kr.co.cking.mission.application.dto.MissionCompleteOutcome;
import kr.co.cking.mission.domain.MissionErrorCode;
import kr.co.cking.mission.presentation.MissionController;
import kr.co.cking.mission.presentation.dto.MissionCompleteRequest;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MissionController.class)
@kr.co.cking.common.security.WithMockJwt
class MissionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private MissionCompletionService missionCompletionService;

    @MockitoBean
    private CreatorSpaceShareMissionCompletionService creatorSpaceShareMissionCompletionService;


    @Test
    void 최초_완료는_202와_EARN_ACCEPTED를_반환한다() throws Exception {
        MissionCompleteRequest request = new MissionCompleteRequest(UUID.randomUUID());
        MissionCompleteOutcome outcome = new MissionCompleteOutcome(
                EarnResultCode.EARN_ACCEPTED, 100L, 1, Instant.parse("2026-09-16T10:00:00Z"));
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
        MissionCompleteRequest request = new MissionCompleteRequest(UUID.randomUUID());
        MissionCompleteOutcome outcome = new MissionCompleteOutcome(
                EarnResultCode.ALREADY_PROCESSED, 100L, 1, Instant.parse("2026-09-16T10:00:00Z"));
        when(missionCompletionService.complete(eq(10L), eq(100L), any())).thenReturn(outcome);

        mockMvc.perform(post("/api/creators/10/missions/100/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("ALREADY_PROCESSED"));
    }

    @Test
    void 별도_인증이_필요한_미션은_409를_반환한다() throws Exception {
        MissionCompleteRequest request = new MissionCompleteRequest(UUID.randomUUID());
        when(missionCompletionService.complete(eq(10L), eq(100L), any()))
                .thenThrow(new BusinessException(MissionErrorCode.MISSION_REQUIRES_VERIFICATION));

        mockMvc.perform(post("/api/creators/10/missions/100/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MISSION_REQUIRES_VERIFICATION"));
    }

    @Test
    @org.springframework.security.test.context.support.WithAnonymousUser
    void JWT가_없으면_401과_UNAUTHORIZED를_반환한다() throws Exception {
        mockMvc.perform(post("/api/creators/10/missions/100/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void requestId가_없으면_400과_VALIDATION_FAILED를_반환한다() throws Exception {
        mockMvc.perform(post("/api/creators/10/missions/100/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void creatorId_또는_missionId가_양수가_아니면_400과_VALIDATION_FAILED를_반환한다() throws Exception {
        String request = "{\"requestId\":\"" + UUID.randomUUID() + "\"}";

        mockMvc.perform(post("/api/creators/0/missions/100/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/creators/10/missions/-1/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(missionCompletionService);
    }

    /** Creator Space 공유 완료는 새 EARN 요청이면 202와 EARN_ACCEPTED를 반환한다. */
    @Test
    void 공유_미션_최초_완료는_202와_EARN_ACCEPTED를_반환한다() throws Exception {
        MissionCompleteRequest request = new MissionCompleteRequest(UUID.randomUUID());
        MissionCompleteOutcome outcome = new MissionCompleteOutcome(
                EarnResultCode.EARN_ACCEPTED, 100L, 1, Instant.parse("2026-09-16T10:00:00Z"));
        when(creatorSpaceShareMissionCompletionService.complete(eq(10L), any())).thenReturn(outcome);

        mockMvc.perform(post("/api/creators/10/missions/share/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value("EARN_ACCEPTED"))
                .andExpect(jsonPath("$.data.missionId").value(100));
    }

    /** Creator Space 공유 완료도 requestId가 없으면 요청 형식 오류를 반환한다. */
    @Test
    void 공유_미션_requestId가_없으면_400과_VALIDATION_FAILED를_반환한다() throws Exception {
        mockMvc.perform(post("/api/creators/10/missions/share/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(creatorSpaceShareMissionCompletionService);
    }

    /** Creator Space 공유 완료는 인증되지 않은 호출자를 허용하지 않는다. */
    @Test
    @org.springframework.security.test.context.support.WithAnonymousUser
    void 공유_미션에_JWT가_없으면_401과_UNAUTHORIZED를_반환한다() throws Exception {
        mockMvc.perform(post("/api/creators/10/missions/share/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
