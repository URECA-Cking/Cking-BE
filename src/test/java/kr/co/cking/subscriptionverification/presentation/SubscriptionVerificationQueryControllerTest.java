package kr.co.cking.subscriptionverification.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.subscriptionverification.application.SubscriptionVerificationPublicStatus;
import kr.co.cking.subscriptionverification.application.SubscriptionVerificationQueryResult;
import kr.co.cking.subscriptionverification.application.SubscriptionVerificationQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SubscriptionVerificationQueryController.class)
class SubscriptionVerificationQueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SubscriptionVerificationQueryService queryService;

    @MockitoBean
    private MemberRepository memberRepository;

    @Test
    void 인증이_없으면_단건_조회를_거부한다() throws Exception {
        mockMvc.perform(get("/api/subscription-verifications/{verificationId}", 123L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 인증이_없으면_최신_조회를_거부한다() throws Exception {
        mockMvc.perform(get(
                        "/api/creators/{creatorId}/missions/{missionId}/subscription-verifications/me/latest",
                        42L,
                        103L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 내_구독_인증_상태를_조회한다() throws Exception {
        given(queryService.getMine(7L, 123L)).willReturn(result());

        mockMvc.perform(get("/api/subscription-verifications/{verificationId}", 123L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.verificationId").value(123))
                .andExpect(jsonPath("$.data.status").value("VERIFIED"))
                .andExpect(jsonPath("$.data.rewarded").value(true))
                .andExpect(jsonPath("$.data.reasonCode").value("SUBSCRIPTION_CONFIRMED"))
                .andExpect(jsonPath("$.data.submittedAt").value("2026-09-28T03:00:00Z"))
                .andExpect(jsonPath("$.data.processedAt").value("2026-09-28T03:00:02Z"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 미션의_내_최신_구독_인증을_조회한다() throws Exception {
        given(queryService.getLatestMine(7L, 42L, 103L)).willReturn(result());

        mockMvc.perform(get(
                        "/api/creators/{creatorId}/missions/{missionId}/subscription-verifications/me/latest",
                        42L,
                        103L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verificationId").value(123));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    @WithMockJwt(memberId = "7")
    void 단건_조회_식별자는_양수여야_한다(long invalidId) throws Exception {
        mockMvc.perform(get("/api/subscription-verifications/{verificationId}", invalidId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        then(queryService).should(never()).getMine(any(), any());
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    @WithMockJwt(memberId = "7")
    void 최신_조회_creatorId는_양수여야_한다(long invalidId) throws Exception {
        mockMvc.perform(get(
                        "/api/creators/{creatorId}/missions/{missionId}/subscription-verifications/me/latest",
                        invalidId,
                        103L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        then(queryService).should(never()).getLatestMine(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    @WithMockJwt(memberId = "7")
    void 최신_조회_missionId는_양수여야_한다(long invalidId) throws Exception {
        mockMvc.perform(get(
                        "/api/creators/{creatorId}/missions/{missionId}/subscription-verifications/me/latest",
                        42L,
                        invalidId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        then(queryService).should(never()).getLatestMine(any(), any(), any());
    }

    private SubscriptionVerificationQueryResult result() {
        return new SubscriptionVerificationQueryResult(
                123L,
                SubscriptionVerificationPublicStatus.VERIFIED,
                true,
                "SUBSCRIPTION_CONFIRMED",
                Instant.parse("2026-09-28T03:00:00Z"),
                Instant.parse("2026-09-28T03:00:02Z")
        );
    }
}
