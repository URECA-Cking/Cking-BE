package kr.co.cking.subscriptionverification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.Mission;
import kr.co.cking.mission.MissionRepository;
import kr.co.cking.mission.domain.MissionErrorCode;
import kr.co.cking.mission.domain.MissionType;
import kr.co.cking.subscriptionverification.domain.CreatorYoutubeChannel;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerification;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationErrorCode;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationStatus;
import kr.co.cking.subscriptionverification.repository.CreatorYoutubeChannelRepository;
import kr.co.cking.subscriptionverification.repository.SubscriptionVerificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SubscriptionVerificationSubmissionValidatorTest {

    private static final Long MEMBER_ID = 7L;
    private static final Long CREATOR_ID = 42L;
    private static final Long MISSION_ID = 103L;
    private static final Instant NOW = Instant.parse("2026-09-29T03:00:00Z");
    private static final String REQUEST_ID = "11111111-1111-1111-1111-111111111111";
    private static final String FINGERPRINT = "a".repeat(64);

    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final MissionRepository missionRepository = mock(MissionRepository.class);
    private final CreatorYoutubeChannelRepository channelRepository =
            mock(CreatorYoutubeChannelRepository.class);
    private final SubscriptionVerificationRepository verificationRepository =
            mock(SubscriptionVerificationRepository.class);
    private final SubscriptionVerificationSubmissionValidator validator =
            new SubscriptionVerificationSubmissionValidator(
                    memberRepository,
                    creatorRepository,
                    missionRepository,
                    channelRepository,
                    verificationRepository);

    @BeforeEach
    void setUp() {
        given(memberRepository.existsById(MEMBER_ID)).willReturn(true);
        given(creatorRepository.existsById(CREATOR_ID)).willReturn(true);
        given(missionRepository.findById(MISSION_ID)).willReturn(Optional.of(
                new Mission(CREATOR_ID, MissionType.YOUTUBE_SUBSCRIPTION, 1, null, null)));
        given(channelRepository.findById(CREATOR_ID)).willReturn(Optional.of(
                new CreatorYoutubeChannel(CREATOR_ID, "예상치 못한 필름", "@unexpectedfilm", NOW)));
        given(verificationRepository.findByRequestId(REQUEST_ID)).willReturn(Optional.empty());
    }

    @Test
    void 구독_인증_미션과_채널을_검증한다() {
        SubscriptionVerificationSubmissionSource source =
                validator.validateSource(MEMBER_ID, CREATOR_ID, MISSION_ID, NOW);

        assertThat(source.mission().getType()).isEqualTo(MissionType.YOUTUBE_SUBSCRIPTION);
        assertThat(source.channel().getChannelHandle()).isEqualTo("@unexpectedfilm");
    }

    @Test
    void 다른_Creator의_미션은_거부한다() {
        given(missionRepository.findById(MISSION_ID)).willReturn(Optional.of(
                new Mission(999L, MissionType.YOUTUBE_SUBSCRIPTION, 1, null, null)));

        assertError(
                () -> validator.validateSource(MEMBER_ID, CREATOR_ID, MISSION_ID, NOW),
                SubscriptionVerificationErrorCode.INVALID_VERIFICATION_MISSION);
    }

    @Test
    void 비활성_미션은_거부한다() {
        given(missionRepository.findById(MISSION_ID)).willReturn(Optional.of(
                new Mission(
                        CREATOR_ID,
                        MissionType.YOUTUBE_SUBSCRIPTION,
                        1,
                        NOW.minusSeconds(60),
                        NOW)));

        assertError(
                () -> validator.validateSource(MEMBER_ID, CREATOR_ID, MISSION_ID, NOW),
                MissionErrorCode.MISSION_INACTIVE);
    }

    @Test
    void 동일_requestId와_동일_fingerprint는_기존_인증을_반환한다() {
        SubscriptionVerification existing = verification(FINGERPRINT, NOW.minusSeconds(60));
        given(verificationRepository.findByRequestId(REQUEST_ID)).willReturn(Optional.of(existing));

        assertThat(validator.validateRequest(
                MEMBER_ID, CREATOR_ID, MISSION_ID, REQUEST_ID, FINGERPRINT, NOW))
                .contains(existing);
    }

    @Test
    void 동일_requestId와_다른_fingerprint는_충돌이다() {
        given(verificationRepository.findByRequestId(REQUEST_ID))
                .willReturn(Optional.of(verification("b".repeat(64), NOW.minusSeconds(60))));

        assertError(
                () -> validator.validateRequest(
                        MEMBER_ID, CREATOR_ID, MISSION_ID, REQUEST_ID, FINGERPRINT, NOW),
                SubscriptionVerificationErrorCode.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void 승인과_진행중_인증은_신규_제출을_차단한다() {
        given(verificationRepository.findByMemberIdAndCreatorIdAndMissionIdAndStatus(
                MEMBER_ID, CREATOR_ID, MISSION_ID, SubscriptionVerificationStatus.APPROVED))
                .willReturn(Optional.of(verification(FINGERPRINT, NOW.minusSeconds(60))));
        assertError(
                () -> validateRequest(),
                SubscriptionVerificationErrorCode.VERIFICATION_ALREADY_APPROVED);

        given(verificationRepository.findByMemberIdAndCreatorIdAndMissionIdAndStatus(
                MEMBER_ID, CREATOR_ID, MISSION_ID, SubscriptionVerificationStatus.APPROVED))
                .willReturn(Optional.empty());
        given(verificationRepository
                .findFirstByMemberIdAndCreatorIdAndMissionIdOrderByCreatedAtDescVerificationIdDesc(
                        MEMBER_ID, CREATOR_ID, MISSION_ID))
                .willReturn(Optional.of(verification(FINGERPRINT, NOW.minusSeconds(60))));
        assertError(
                () -> validateRequest(),
                SubscriptionVerificationErrorCode.VERIFICATION_IN_PROGRESS);
    }

    @Test
    void 최근_30초와_UTC_하루_5회_제출을_제한한다() {
        given(verificationRepository
                .findFirstByMemberIdAndCreatorIdAndMissionIdOrderByCreatedAtDescVerificationIdDesc(
                        MEMBER_ID, CREATOR_ID, MISSION_ID))
                .willReturn(Optional.of(rejectedVerification(FINGERPRINT, NOW.minusSeconds(29))));
        assertError(
                () -> validateRequest(),
                SubscriptionVerificationErrorCode.VERIFICATION_SUBMISSION_LIMIT_EXCEEDED);

        given(verificationRepository
                .findFirstByMemberIdAndCreatorIdAndMissionIdOrderByCreatedAtDescVerificationIdDesc(
                        MEMBER_ID, CREATOR_ID, MISSION_ID))
                .willReturn(Optional.empty());
        given(verificationRepository
                .countByMemberIdAndCreatorIdAndMissionIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                        any(), any(), any(), any(), any()))
                .willReturn(5L);
        assertError(
                () -> validateRequest(),
                SubscriptionVerificationErrorCode.VERIFICATION_SUBMISSION_LIMIT_EXCEEDED);
    }

    private Optional<SubscriptionVerification> validateRequest() {
        return validator.validateRequest(
                MEMBER_ID, CREATOR_ID, MISSION_ID, REQUEST_ID, FINGERPRINT, NOW);
    }

    private SubscriptionVerification verification(String fingerprint, Instant createdAt) {
        return SubscriptionVerification.pending(
                MEMBER_ID,
                CREATOR_ID,
                MISSION_ID,
                REQUEST_ID,
                fingerprint,
                "예상치 못한 필름",
                "@unexpectedfilm",
                "subscription-verifications/2026/09/id/image.jpg",
                "b".repeat(64),
                "JPEG_V1",
                UUID.randomUUID().toString(),
                createdAt);
    }

    private SubscriptionVerification rejectedVerification(String fingerprint, Instant createdAt) {
        SubscriptionVerification verification = verification(fingerprint, createdAt);
        String processingToken = UUID.randomUUID().toString();
        Instant processingStartedAt = createdAt.plusSeconds(1);
        verification.startProcessing(
                processingToken, processingStartedAt, processingStartedAt.plusSeconds(60));
        verification.reject(processingToken, "NOT_SUBSCRIBED", processingStartedAt.plusSeconds(1));
        return verification;
    }

    private void assertError(
            Runnable action,
            kr.co.cking.common.exception.ErrorCode expected
    ) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(expected);
    }
}
