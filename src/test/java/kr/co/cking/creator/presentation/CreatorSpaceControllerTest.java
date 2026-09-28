package kr.co.cking.creator.presentation;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.creator.application.CreatorSpaceProfileService;
import kr.co.cking.creator.application.dto.CreatorSpaceProfileFields;
import kr.co.cking.creator.application.dto.CreatorSpaceView;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.domain.CreatorSpaceTemplate;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CreatorSpaceController.class)
class CreatorSpaceControllerTest {

    private static final String UPDATE_BODY = """
            {
              "introText": "새 소개",
              "profileImageUrl": "https://img/p2.png",
              "bannerImageUrl": "https://img/b2.png",
              "homeTabEnabled": true,
              "missionsTabEnabled": false,
              "postsTabEnabled": true,
              "eventsTabEnabled": false
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreatorSpaceProfileService profileService;

    @MockitoBean
    private MemberRepository memberRepository;

    @Test
    void publicSpaceIsReadableWithoutJwt() throws Exception {
        given(profileService.findByCreatorId(42L)).willReturn(view());

        mockMvc.perform(get("/api/creators/{creatorId}/space", 42L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.creatorId").value(42))
                .andExpect(jsonPath("$.data.creatorName").value("크리에이터"))
                .andExpect(jsonPath("$.data.slug").value("creator-42"))
                .andExpect(jsonPath("$.data.introText").value("소개"))
                .andExpect(jsonPath("$.data.missionsTabEnabled").value(false));
    }

    @Test
    void sharedUrlResolvesSpaceBySlugWithoutJwt() throws Exception {
        given(profileService.findBySlug("creator-42")).willReturn(view());

        mockMvc.perform(get("/api/creator-spaces/{slug}", "creator-42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.creatorId").value(42))
                .andExpect(jsonPath("$.data.slug").value("creator-42"));
    }

    @Test
    void publicSpaceReturnsNotFoundEnvelopeWhenMissing() throws Exception {
        given(profileService.findByCreatorId(42L))
                .willThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        mockMvc.perform(get("/api/creators/{creatorId}/space", 42L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void sharedUrlReturnsNotFoundEnvelopeWhenSlugIsMissing() throws Exception {
        given(profileService.findBySlug("missing"))
                .willThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        mockMvc.perform(get("/api/creator-spaces/{slug}", "missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void mySpaceUsesJwtMemberId() throws Exception {
        given(profileService.findMine(7L)).willReturn(view());

        mockMvc.perform(get("/api/creator/space"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slug").value("creator-42"));
    }

    @Test
    @WithMockJwt(memberId = "8")
    void mySpaceReturnsForbiddenEnvelopeForNonCreator() throws Exception {
        given(profileService.findMine(8L)).willThrow(new BusinessException(CommonErrorCode.FORBIDDEN));

        mockMvc.perform(get("/api/creator/space"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void mySpaceRequiresJwt() throws Exception {
        mockMvc.perform(get("/api/creator/space"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void updateMySpacePassesAllProfileFields() throws Exception {
        CreatorSpaceProfileFields expected = new CreatorSpaceProfileFields(
                "새 소개", "https://img/p2.png", "https://img/b2.png", true, false, true, false
        );
        given(profileService.updateMine(7L, expected)).willReturn(view());

        mockMvc.perform(patch("/api/creator/space").contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
        then(profileService).should().updateMine(7L, expected);
    }

    @Test
    void updateMySpaceRequiresJwt() throws Exception {
        mockMvc.perform(patch("/api/creator/space").contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY))
                .andExpect(status().isUnauthorized());
        then(profileService).should(never()).updateMine(any(), any());
    }

    /** 모든 필드를 한 번에 교체하므로 누락된 탭 값도 검증 실패다. */
    @Test
    @WithMockJwt(memberId = "7")
    void updateMySpaceRejectsBlankOrMissingFields() throws Exception {
        mockMvc.perform(patch("/api/creator/space")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "introText": " ",
                                  "profileImageUrl": "https://img/p2.png",
                                  "bannerImageUrl": "https://img/b2.png",
                                  "homeTabEnabled": true
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        then(profileService).should(never()).updateMine(eq(7L), any());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void updateMySpaceRejectsTooLongIntro() throws Exception {
        String body = UPDATE_BODY.replace("새 소개", "a".repeat(501));

        mockMvc.perform(patch("/api/creator/space").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private CreatorSpaceView view() {
        CreatorSpace space = CreatorSpace.fromTemplate(42L, new CreatorSpaceTemplate(
                1L, "소개", "https://img/profile.png", "https://img/banner.png", "creator-{creatorId}",
                true, false, true, false
        ), "creator-42");
        return new CreatorSpaceView(space, "크리에이터");
    }
}
