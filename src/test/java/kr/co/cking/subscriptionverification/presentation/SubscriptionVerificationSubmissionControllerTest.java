package kr.co.cking.subscriptionverification.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.subscriptionverification.application.SubscriptionVerificationFingerprint;
import kr.co.cking.subscriptionverification.application.SubscriptionVerificationAvailability;
import kr.co.cking.subscriptionverification.application.SubscriptionVerificationSubmissionCommand;
import kr.co.cking.subscriptionverification.application.SubscriptionVerificationSubmissionResult;
import kr.co.cking.subscriptionverification.application.SubscriptionVerificationSubmissionService;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerification;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.unit.DataSize;
import org.mockito.ArgumentCaptor;

@WebMvcTest(SubscriptionVerificationSubmissionController.class)
class SubscriptionVerificationSubmissionControllerTest {

    private static final String REQUEST_ID = "11111111-1111-1111-1111-111111111111";

    @Autowired
    private MockMvc mockMvc;

    @Value("${spring.servlet.multipart.max-file-size}")
    private DataSize maxFileSize;

    @Value("${spring.servlet.multipart.max-request-size}")
    private DataSize maxRequestSize;

    @MockitoBean
    private SubscriptionVerificationSubmissionService submissionService;

    @MockitoBean
    private SubscriptionVerificationAvailability availability;

    @MockitoBean
    private MemberRepository memberRepository;

    @Test
    void 인증이_없으면_제출을_거부한다() throws Exception {
        mockMvc.perform(request(42L, 103L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 기능이_꺼져_있으면_Service_호출_전에_503을_반환한다() throws Exception {
        doThrow(new BusinessException(SubscriptionVerificationErrorCode.VERIFICATION_UNAVAILABLE))
                .when(availability).requireSubmissionEnabled();

        mockMvc.perform(request(42L, 103L))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("VERIFICATION_UNAVAILABLE"));
        then(submissionService).should(never()).submit(any());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 신규_제출은_202를_반환한다() throws Exception {
        given(submissionService.submit(any()))
                .willReturn(new SubscriptionVerificationSubmissionResult(verification(), true));

        mockMvc.perform(request(42L, 103L))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.verificationId").value(123))
                .andExpect(jsonPath("$.data.status").value("VERIFYING"))
                .andExpect(jsonPath("$.data.rewarded").value(false))
                .andExpect(jsonPath("$.data.submittedAt").value("2026-09-29T03:00:00Z"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void _1MB를_초과한_이미지도_5MB_계약_안에서는_Controller를_통과한다() throws Exception {
        byte[] imageBytes = new byte[1024 * 1024 + 1];
        given(submissionService.submit(any()))
                .willReturn(new SubscriptionVerificationSubmissionResult(verification(), true));

        mockMvc.perform(multipart(
                        "/api/creators/{creatorId}/missions/{missionId}/subscription-verifications",
                        42L,
                        103L)
                        .file(new MockMultipartFile(
                                "image", "proof.jpg", "image/jpeg", imageBytes))
                        .param("requestId", REQUEST_ID))
                .andExpect(status().isAccepted());

        ArgumentCaptor<SubscriptionVerificationSubmissionCommand> commandCaptor =
                ArgumentCaptor.forClass(SubscriptionVerificationSubmissionCommand.class);
        then(submissionService).should().submit(commandCaptor.capture());
        assertThat(commandCaptor.getValue().imageBytes()).hasSize(imageBytes.length);
        assertThat(maxFileSize).isEqualTo(DataSize.ofMegabytes(5));
        assertThat(maxRequestSize).isEqualTo(DataSize.ofMegabytes(6));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 멱등_재요청은_200을_반환한다() throws Exception {
        given(submissionService.submit(any()))
                .willReturn(new SubscriptionVerificationSubmissionResult(verification(), false));

        mockMvc.perform(request(42L, 103L))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    @WithMockJwt(memberId = "7")
    void creatorId는_양수여야_한다(long creatorId) throws Exception {
        mockMvc.perform(request(creatorId, 103L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        then(submissionService).should(never()).submit(any());
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    @WithMockJwt(memberId = "7")
    void missionId는_양수여야_한다(long missionId) throws Exception {
        mockMvc.perform(request(42L, missionId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        then(submissionService).should(never()).submit(any());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void requestId가_UUID가_아니면_400이다() throws Exception {
        mockMvc.perform(multipart("/api/creators/{creatorId}/missions/{missionId}/subscription-verifications", 42L, 103L)
                        .file(image())
                        .param("requestId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        then(submissionService).should(never()).submit(any());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 이미지가_없으면_400이다() throws Exception {
        mockMvc.perform(multipart(
                        "/api/creators/{creatorId}/missions/{missionId}/subscription-verifications",
                        42L,
                        103L)
                        .param("requestId", REQUEST_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        then(submissionService).should(never()).submit(any());
    }

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder request(
            long creatorId,
            long missionId
    ) {
        return multipart("/api/creators/{creatorId}/missions/{missionId}/subscription-verifications", creatorId, missionId)
                .file(image())
                .param("requestId", REQUEST_ID);
    }

    private MockMultipartFile image() {
        return new MockMultipartFile(
                "image", "proof.jpg", "image/jpeg", "image".getBytes(StandardCharsets.UTF_8));
    }

    private SubscriptionVerification verification() {
        SubscriptionVerification verification = SubscriptionVerification.pending(
                7L,
                42L,
                103L,
                REQUEST_ID,
                SubscriptionVerificationFingerprint.calculate(7L, 42L, 103L, "b".repeat(64)),
                "예상치 못한 필름",
                "@unexpectedfilm",
                "subscription-verifications/2026/09/id/image.jpg",
                "b".repeat(64),
                "JPEG_V1",
                UUID.randomUUID().toString(),
                Instant.parse("2026-09-29T03:00:00Z"));
        ReflectionTestUtils.setField(verification, "verificationId", 123L);
        return verification;
    }
}
