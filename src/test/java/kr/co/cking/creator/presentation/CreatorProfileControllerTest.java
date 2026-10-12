package kr.co.cking.creator.presentation;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.creator.application.CreatorActivityNameService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CreatorProfileController.class)
class CreatorProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreatorActivityNameService activityNameService;

    @Test
    @WithMockJwt(memberId = "7")
    void 내_활동명을_조회한다() throws Exception {
        given(activityNameService.findMine(7L)).willReturn(profile());

        mockMvc.perform(get("/api/creator/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activityName").value("cking_creator"))
                .andExpect(jsonPath("$.data.activityNameConfigured").value(true))
                .andExpect(jsonPath("$.data.activityNameChangeableAt").value("2026-10-26T00:00:00Z"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 내_활동명을_수정한다() throws Exception {
        given(activityNameService.updateMine(7L, "new_creator")).willReturn(profile());

        mockMvc.perform(put("/api/creator/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activityName\":\"new_creator\"}"))
                .andExpect(status().isOk());

        then(activityNameService).should().updateMine(7L, "new_creator");
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 빈_활동명은_거절한다() throws Exception {
        mockMvc.perform(put("/api/creator/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activityName\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        then(activityNameService).should(never()).updateMine(any(), any());
    }

    private CreatorActivityNameService.ActivityNameProfile profile() {
        return new CreatorActivityNameService.ActivityNameProfile(
                10L, "cking_creator", true, Instant.parse("2026-10-26T00:00:00Z"), "old_creator");
    }
}
