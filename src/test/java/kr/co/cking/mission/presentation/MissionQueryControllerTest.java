package kr.co.cking.mission.presentation;

import tools.jackson.databind.ObjectMapper;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.mission.application.MissionQueryService;
import kr.co.cking.mission.application.dto.MissionQueryItem;
import kr.co.cking.mission.domain.MissionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MissionQueryController.class)
@kr.co.cking.common.security.WithMockJwt(memberId = "7")
class MissionQueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private MissionQueryService missionQueryService;

    @Test
    void creator의_구독_인증_미션과_ONCE_완료_상태를_공통응답으로_반환한다() throws Exception {
        MissionQueryItem item = new MissionQueryItem(
                101L, MissionType.YOUTUBE_SUBSCRIPTION, 1,
                null, null, true);
        when(missionQueryService.findMissions(11L, 7L)).thenReturn(List.of(item));

        mockMvc.perform(get("/api/creators/11/missions")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].missionId").value(101))
                .andExpect(jsonPath("$.data[0].type").value("YOUTUBE_SUBSCRIPTION"))
                .andExpect(jsonPath("$.data[0].rewardAmount").value(1))
                .andExpect(jsonPath("$.data[0].activeFrom").doesNotExist())
                .andExpect(jsonPath("$.data[0].activeTo").doesNotExist())
                .andExpect(jsonPath("$.data[0].completedToday").value(true));
    }

    @Test
    @org.springframework.security.test.context.support.WithAnonymousUser
    void JWT가_없으면_UNAUTHORIZED를_반환한다() throws Exception {
        mockMvc.perform(get("/api/creators/11/missions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 인증된_사용자_또는_creatorId가_존재하지_않으면_RESOURCE_NOT_FOUND다() throws Exception {
        when(missionQueryService.findMissions(11L, 7L))
                .thenThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        mockMvc.perform(get("/api/creators/11/missions"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }
}
