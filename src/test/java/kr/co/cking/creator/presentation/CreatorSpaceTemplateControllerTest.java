package kr.co.cking.creator.presentation;

import kr.co.cking.creator.application.CreatorSpaceTemplateService;
import kr.co.cking.creator.domain.CreatorSpaceTemplate;
import kr.co.cking.common.security.WithMockJwt;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CreatorSpaceTemplateController.class)
@WithMockJwt(memberId = "1")
class CreatorSpaceTemplateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreatorSpaceTemplateService templateService;

    @MockitoBean
    private MemberRepository memberRepository;

    @Test
    void creationReturnsCreatedResponseEnvelope() throws Exception {
        given(templateService.create(eq(1L), any())).willReturn(templateWithId(10L));

        mockMvc.perform(post("/api/admin/creator-space-templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "introText": "소개",
                                  "profileImageUrl": "https://img/profile.png",
                                  "bannerImageUrl": "https://img/banner.png",
                                  "slugRule": "creator-{creatorId}",
                                  "homeTabEnabled": true,
                                  "missionsTabEnabled": true,
                                  "postsTabEnabled": true,
                                  "eventsTabEnabled": true
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.templateId").value(10))
                .andExpect(jsonPath("$.data.active").value(false));

        then(templateService).should().create(eq(1L), any());
    }

    @Test
    void blankIntroTextReturnsValidationFailedEnvelope() throws Exception {
        mockMvc.perform(post("/api/admin/creator-space-templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "introText": "   ",
                                  "profileImageUrl": "https://img/profile.png",
                                  "bannerImageUrl": "https://img/banner.png",
                                  "slugRule": "creator-{creatorId}",
                                  "homeTabEnabled": true,
                                  "missionsTabEnabled": true,
                                  "postsTabEnabled": true,
                                  "eventsTabEnabled": true
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void listUsesJwtMemberIdWithoutUserIdQuery() throws Exception {
        given(templateService.findAllForAdmin(1L, PageRequest.of(0, 20)))
                .willReturn(new PageImpl<>(List.of(templateWithId(10L)), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/admin/creator-space-templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].templateId").value(10));

        then(templateService).should().findAllForAdmin(1L, PageRequest.of(0, 20));
    }

    @Test
    void detailReturnsTemplate() throws Exception {
        given(templateService.findForAdmin(1L, 10L)).willReturn(templateWithId(10L));

        mockMvc.perform(get("/api/admin/creator-space-templates/{templateId}", 10L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.templateId").value(10));

        then(templateService).should().findForAdmin(1L, 10L);
    }

    @Test
    void activationReturnsActivatedTemplate() throws Exception {
        CreatorSpaceTemplate template = templateWithId(10L);
        template.activate(1L);
        given(templateService.activate(1L, 10L)).willReturn(template);

        mockMvc.perform(post("/api/admin/creator-space-templates/{templateId}/activate", 10L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(true));

        then(templateService).should().activate(1L, 10L);
    }

    @Test
    void updateReturnsUpdatedTemplate() throws Exception {
        CreatorSpaceTemplate template = templateWithId(10L);
        template.update(1L, "새 소개", "https://img/p2.png", "https://img/b2.png", "new-{creatorId}", false, false, false, true);
        given(templateService.update(eq(1L), eq(10L), any())).willReturn(template);

        mockMvc.perform(patch("/api/admin/creator-space-templates/{templateId}", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "introText": "새 소개",
                                  "profileImageUrl": "https://img/p2.png",
                                  "bannerImageUrl": "https://img/b2.png",
                                  "slugRule": "new-{creatorId}",
                                  "homeTabEnabled": false,
                                  "missionsTabEnabled": false,
                                  "postsTabEnabled": false,
                                  "eventsTabEnabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.introText").value("새 소개"))
                .andExpect(jsonPath("$.data.missionsTabEnabled").value(false))
                .andExpect(jsonPath("$.data.eventsTabEnabled").value(true));

        then(templateService).should().update(eq(1L), eq(10L), any());
    }

    private CreatorSpaceTemplate templateWithId(Long templateId) {
        CreatorSpaceTemplate template = new CreatorSpaceTemplate(
                1L, "소개", "https://img/profile.png", "https://img/banner.png", "creator-{creatorId}",
                true, true, true, true
        );
        ReflectionTestUtils.setField(template, "templateId", templateId);
        return template;
    }
}
