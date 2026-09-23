package kr.co.cking.mission.presentation;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.mission.application.CommonMissionQueryService;
import kr.co.cking.mission.application.dto.CommonMissionQueryItem;
import kr.co.cking.mission.domain.CommonMissionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CommonMissionQueryController.class)
@kr.co.cking.common.security.WithMockJwt(memberId = "7")
class CommonMissionQueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CommonMissionQueryService commonMissionQueryService;

    @Test
    void 공용_미션과_completedToday를_공통응답으로_반환한다() throws Exception {
        CommonMissionQueryItem item = new CommonMissionQueryItem(
                101L, CommonMissionType.ATTENDANCE, 1,
                null, null, true);
        when(commonMissionQueryService.findMissions(7L)).thenReturn(List.of(item));

        mockMvc.perform(get("/api/missions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].missionId").value(101))
                .andExpect(jsonPath("$.data[0].type").value("ATTENDANCE"))
                .andExpect(jsonPath("$.data[0].completedToday").value(true));
    }

    @Test
    @org.springframework.security.test.context.support.WithAnonymousUser
    void JWT가_없으면_UNAUTHORIZED를_반환한다() throws Exception {
        mockMvc.perform(get("/api/missions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 인증된_사용자가_존재하지_않으면_RESOURCE_NOT_FOUND다() throws Exception {
        when(commonMissionQueryService.findMissions(7L))
                .thenThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        mockMvc.perform(get("/api/missions"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }
}
