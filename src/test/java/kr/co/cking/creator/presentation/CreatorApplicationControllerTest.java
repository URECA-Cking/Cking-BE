package kr.co.cking.creator.presentation;

import kr.co.cking.creator.application.CreatorApplicationService;
import kr.co.cking.creator.domain.CreatorApplication;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CreatorApplicationController.class)
@kr.co.cking.common.security.WithMockJwt(memberId = "10")
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
                        .content("{\"rejectReason\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 관리자_승인은_JWT의_memberId를_서비스에_전달한다() throws Exception {
        CreatorApplication application = applicationWithId(1L);
        application.approve(10L);
        given(creatorApplicationService.approve(10L, 1L)).willReturn(application);

        mockMvc.perform(post("/api/admin/creator-applications/{id}/approve", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        then(creatorApplicationService).should().approve(10L, 1L);
    }

    @Test
    void 관리자_목록_조회는_JWT의_memberId를_서비스에_전달한다() throws Exception {
        CreatorApplication application = applicationWithId(1L);
        PageRequest pageable = PageRequest.of(0, 20);
        given(creatorApplicationService.findAllForAdmin(10L, pageable))
                .willReturn(new PageImpl<>(
                        List.of(new CreatorApplicationService.AdminApplication(application, "신청자")), pageable, 1));

        mockMvc.perform(get("/api/admin/creator-applications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].applicationId").value(1));

        then(creatorApplicationService).should().findAllForAdmin(10L, pageable);
    }

    @Test
    void 관리자_거절은_JWT의_memberId를_서비스에_전달한다() throws Exception {
        CreatorApplication application = applicationWithId(1L);
        application.reject(10L, "거절 사유");
        given(creatorApplicationService.reject(10L, 1L, "거절 사유")).willReturn(application);

        mockMvc.perform(post("/api/admin/creator-applications/{id}/reject", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rejectReason\":\"거절 사유\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        then(creatorApplicationService).should().reject(10L, 1L, "거절 사유");
    }

    private CreatorApplication applicationWithId(Long id) {
        CreatorApplication application = new CreatorApplication(10L);
        ReflectionTestUtils.setField(application, "id", id);
        return application;
    }
}
