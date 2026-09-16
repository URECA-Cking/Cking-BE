package kr.co.cking.creator.presentation;

import kr.co.cking.creator.application.CreatorApplicationService;
import kr.co.cking.creator.domain.CreatorApplication;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CreatorApplicationController.class)
class CreatorApplicationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreatorApplicationService creatorApplicationService;

    @MockitoBean
    private MemberRepository memberRepository;

    @Test
    void applicationCreationReturnsCreatedResponseEnvelope() throws Exception {
        CreatorApplication application = applicationWithId(1L);
        given(creatorApplicationService.apply(10L))
                .willReturn(new CreatorApplicationService.ApplyResult(application, true));

        mockMvc.perform(post("/api/creator/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":10}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.applicationId").value(1))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    void existingPendingApplicationIsReturnedWithOkStatus() throws Exception {
        CreatorApplication application = applicationWithId(1L);
        given(creatorApplicationService.apply(10L))
                .willReturn(new CreatorApplicationService.ApplyResult(application, false));

        mockMvc.perform(post("/api/creator/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applicationId").value(1));
    }

    @Test
    void blankRejectReasonReturnsValidationFailedEnvelope() throws Exception {
        mockMvc.perform(post("/api/admin/creator-applications/{id}/reject", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1,\"rejectReason\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private CreatorApplication applicationWithId(Long id) {
        CreatorApplication application = new CreatorApplication(10L);
        ReflectionTestUtils.setField(application, "id", id);
        return application;
    }
}
