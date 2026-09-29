package kr.co.cking.subscriptionverification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.Instant;
import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerification;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationErrorCode;
import kr.co.cking.subscriptionverification.repository.SubscriptionVerificationRepository;
import kr.co.cking.subscriptionverification.repository.SubscriptionVerificationImageReuseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SubscriptionVerificationQueryServiceTest {

    private static final Long MEMBER_ID = 7L;
    private static final Long CREATOR_ID = 42L;
    private static final Long MISSION_ID = 103L;
    private static final Long VERIFICATION_ID = 123L;

    private final MemberQueryService memberQueryService = mock(MemberQueryService.class);
    private final SubscriptionVerificationRepository verificationRepository =
            mock(SubscriptionVerificationRepository.class);
    private final SubscriptionVerificationImageReuseRepository imageReuseRepository =
            mock(SubscriptionVerificationImageReuseRepository.class);
    private final SubscriptionVerificationQueryService service =
            new SubscriptionVerificationQueryService(
                    memberQueryService, verificationRepository, imageReuseRepository);

    @Test
    void 본인_인증을_공개_상태로_조회한다() {
        SubscriptionVerification verification = verification(MEMBER_ID);
        given(verificationRepository.findById(VERIFICATION_ID)).willReturn(Optional.of(verification));

        SubscriptionVerificationQueryResult result = service.getMine(MEMBER_ID, VERIFICATION_ID);

        then(memberQueryService).should().validateExists(MEMBER_ID);
        assertThat(result.verificationId()).isEqualTo(VERIFICATION_ID);
        assertThat(result.status()).isEqualTo(SubscriptionVerificationPublicStatus.VERIFYING);
        assertThat(result.rewarded()).isFalse();
        assertThat(result.submittedAt()).isEqualTo(Instant.parse("2026-09-28T03:00:00Z"));
    }

    @Test
    void 보상이_승인된_인증만_VERIFIED와_rewarded_true로_조회한다() {
        SubscriptionVerification verification = verification(MEMBER_ID);
        String token = "30000000-0000-4000-8000-000000000001";
        Instant startedAt = Instant.parse("2026-09-28T03:00:01Z");
        verification.startProcessing(token, startedAt, startedAt.plusSeconds(60));
        verification.approve(token, startedAt.plusSeconds(1));
        verification.acceptReward(startedAt.plusSeconds(2));
        given(verificationRepository.findById(VERIFICATION_ID)).willReturn(Optional.of(verification));

        SubscriptionVerificationQueryResult result = service.getMine(MEMBER_ID, VERIFICATION_ID);

        assertThat(result.status()).isEqualTo(SubscriptionVerificationPublicStatus.VERIFIED);
        assertThat(result.rewarded()).isTrue();
        assertThat(result.processedAt()).isEqualTo(startedAt.plusSeconds(1));
    }

    @Test
    void 존재하지_않는_인증은_VERIFICATION_NOT_FOUND다() {
        given(verificationRepository.findById(VERIFICATION_ID)).willReturn(Optional.empty());

        assertError(
                () -> service.getMine(MEMBER_ID, VERIFICATION_ID),
                SubscriptionVerificationErrorCode.VERIFICATION_NOT_FOUND);
    }

    @Test
    void 다른_사용자의_인증은_FORBIDDEN이다() {
        given(verificationRepository.findById(VERIFICATION_ID)).willReturn(Optional.of(verification(99L)));

        assertError(() -> service.getMine(MEMBER_ID, VERIFICATION_ID), CommonErrorCode.FORBIDDEN);
    }

    @Test
    void 해당_Creator_Mission의_내_최신_인증을_조회한다() {
        SubscriptionVerification verification = verification(MEMBER_ID);
        given(verificationRepository
                .findFirstByMemberIdAndCreatorIdAndMissionIdOrderByCreatedAtDescVerificationIdDesc(
                        MEMBER_ID, CREATOR_ID, MISSION_ID))
                .willReturn(Optional.of(verification));

        SubscriptionVerificationQueryResult result =
                service.getLatestMine(MEMBER_ID, CREATOR_ID, MISSION_ID);

        assertThat(result.verificationId()).isEqualTo(VERIFICATION_ID);
        then(memberQueryService).should().validateExists(MEMBER_ID);
    }

    @Test
    void 최신_인증이_없으면_VERIFICATION_NOT_FOUND다() {
        given(verificationRepository
                .findFirstByMemberIdAndCreatorIdAndMissionIdOrderByCreatedAtDescVerificationIdDesc(
                        MEMBER_ID, CREATOR_ID, MISSION_ID))
                .willReturn(Optional.empty());

        assertError(
                () -> service.getLatestMine(MEMBER_ID, CREATOR_ID, MISSION_ID),
                SubscriptionVerificationErrorCode.VERIFICATION_NOT_FOUND);
    }

    @Test
    void Member가_없으면_인증을_조회하지_않는다() {
        org.mockito.BDDMockito.willThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND))
                .given(memberQueryService).validateExists(MEMBER_ID);

        assertError(() -> service.getMine(MEMBER_ID, VERIFICATION_ID), CommonErrorCode.RESOURCE_NOT_FOUND);

        then(verificationRepository).should(never()).findById(VERIFICATION_ID);
    }

    private SubscriptionVerification verification(Long memberId) {
        SubscriptionVerification verification = SubscriptionVerification.pending(
                memberId,
                CREATOR_ID,
                MISSION_ID,
                "10000000-0000-4000-8000-000000000001",
                "a".repeat(64),
                "예상치 못한 필름",
                "@unexpectedfilm",
                "subscription-verifications/2026/09/id/image.jpg",
                "b".repeat(64),
                "JPEG_V1",
                "20000000-0000-4000-8000-000000000001",
                Instant.parse("2026-09-28T03:00:00Z")
        );
        ReflectionTestUtils.setField(verification, "verificationId", VERIFICATION_ID);
        return verification;
    }

    private void assertError(Runnable action, kr.co.cking.common.exception.ErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(errorCode);
    }
}
