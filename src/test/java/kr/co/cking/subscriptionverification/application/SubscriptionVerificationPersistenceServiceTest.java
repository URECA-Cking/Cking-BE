package kr.co.cking.subscriptionverification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import kr.co.cking.mission.Mission;
import kr.co.cking.mission.domain.MissionType;
import kr.co.cking.subscriptionverification.domain.CreatorYoutubeChannel;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerification;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationStatus;
import kr.co.cking.subscriptionverification.domain.VerificationRewardStatus;
import kr.co.cking.subscriptionverification.repository.SubscriptionVerificationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

class SubscriptionVerificationPersistenceServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-29T23:30:00Z");

    private final SubscriptionVerificationSubmissionValidator validator =
            mock(SubscriptionVerificationSubmissionValidator.class);
    private final SubscriptionVerificationRepository repository =
            mock(SubscriptionVerificationRepository.class);
    private final SubscriptionVerificationImageReuseDetectionService imageReuseDetectionService =
            mock(SubscriptionVerificationImageReuseDetectionService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final SubscriptionVerificationPersistenceService service =
            new SubscriptionVerificationPersistenceService(
                    validator,
                    repository,
                    imageReuseDetectionService,
                    eventPublisher,
                    java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));

    @Test
    void 잠금_후_채널과_보상_입력을_동결해_PENDING을_저장한다() {
        CreatorYoutubeChannel channel = new CreatorYoutubeChannel(
                42L, "예상치 못한 필름", "@UnexpectedFilm", NOW.minusSeconds(60));
        given(validator.validateSourceForUpdate(7L, 42L, 103L, NOW))
                .willReturn(new SubscriptionVerificationSubmissionSource(
                        new Mission(42L, MissionType.YOUTUBE_SUBSCRIPTION, 1, null, null),
                        channel));
        given(validator.validateRequest(any(), any(), any(), any(), any(), any()))
                .willReturn(Optional.empty());
        given(repository.saveAndFlush(any())).willAnswer(invocation -> {
            SubscriptionVerification verification = invocation.getArgument(0);
            ReflectionTestUtils.setField(verification, "verificationId", 123L);
            return verification;
        });

        SubscriptionVerificationSubmissionResult result = service.create(command());

        assertThat(result.created()).isTrue();
        assertThat(result.verification().getStatus()).isEqualTo(SubscriptionVerificationStatus.PENDING);
        assertThat(result.verification().getRewardStatus()).isEqualTo(VerificationRewardStatus.NOT_REQUESTED);
        assertThat(result.verification().getRewardPeriodKey()).isEqualTo("2026-09-29");
        assertThat(result.verification().getTargetChannelName()).isEqualTo("예상치 못한 필름");
        assertThat(result.verification().getTargetChannelHandle()).isEqualTo("@unexpectedfilm");
        InOrder inOrder = org.mockito.Mockito.inOrder(imageReuseDetectionService, repository);
        inOrder.verify(imageReuseDetectionService).lockImageHash("b".repeat(64), NOW);
        inOrder.verify(repository).saveAndFlush(result.verification());
        inOrder.verify(imageReuseDetectionService).detectAndRecord(result.verification(), NOW);

        ArgumentCaptor<SubscriptionVerificationSubmittedEvent> eventCaptor =
                ArgumentCaptor.forClass(SubscriptionVerificationSubmittedEvent.class);
        then(eventPublisher).should().publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().verificationId()).isEqualTo(123L);
    }

    @Test
    void 잠금_후_기존_requestId를_발견하면_새로_저장하지_않는다() {
        SubscriptionVerification existing = SubscriptionVerification.pending(
                7L, 42L, 103L,
                "11111111-1111-1111-1111-111111111111",
                "a".repeat(64),
                "채널", "@channel", "key", "b".repeat(64), "JPEG_V1",
                UUID.randomUUID().toString(), NOW.minusSeconds(60));
        given(validator.validateSourceForUpdate(7L, 42L, 103L, NOW))
                .willReturn(new SubscriptionVerificationSubmissionSource(
                        new Mission(42L, MissionType.YOUTUBE_SUBSCRIPTION, 1, null, null),
                        new CreatorYoutubeChannel(42L, "채널", "@channel", NOW)));
        given(validator.validateRequest(any(), any(), any(), any(), any(), any()))
                .willReturn(Optional.of(existing));

        SubscriptionVerificationSubmissionResult result = service.create(command());

        assertThat(result.created()).isFalse();
        assertThat(result.verification()).isSameAs(existing);
        then(repository).shouldHaveNoInteractions();
        then(imageReuseDetectionService).shouldHaveNoInteractions();
        then(eventPublisher).shouldHaveNoInteractions();
    }

    private SubscriptionVerificationPersistenceCommand command() {
        return new SubscriptionVerificationPersistenceCommand(
                7L,
                42L,
                103L,
                "11111111-1111-1111-1111-111111111111",
                "a".repeat(64),
                "subscription-verifications/2026/09/id/image.jpg",
                "b".repeat(64),
                "JPEG_V1",
                "22222222-2222-2222-2222-222222222222");
    }
}
