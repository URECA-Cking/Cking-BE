package kr.co.cking.subscriptionverification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerification;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationImageHashLock;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationImageReuse;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationImageReuseType;
import kr.co.cking.subscriptionverification.repository.SubscriptionVerificationImageHashLockRepository;
import kr.co.cking.subscriptionverification.repository.SubscriptionVerificationImageReuseRepository;
import kr.co.cking.subscriptionverification.repository.SubscriptionVerificationRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class SubscriptionVerificationImageReuseDetectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-29T03:00:00Z");
    private final SubscriptionVerificationImageHashLockRepository hashLockRepository = mock();
    private final SubscriptionVerificationRepository verificationRepository = mock();
    private final SubscriptionVerificationImageReuseRepository imageReuseRepository = mock();
    private final SubscriptionVerificationImageReuseDetectionService service =
            new SubscriptionVerificationImageReuseDetectionService(
                    hashLockRepository, verificationRepository, imageReuseRepository);

    /** 최초 제출과 세 가지 재사용 관계를 이전 Verification 기준으로 기록한다. */
    @ParameterizedTest
    @MethodSource("reuseCases")
    void 이미지_재사용_유형을_기록한다(
            SubscriptionVerification current,
            SubscriptionVerification previous,
            SubscriptionVerificationImageReuseType expectedType
    ) {
        given(hashLockRepository.findByImageSha256ForUpdate(current.getImageSha256()))
                .willReturn(Optional.of(mock(SubscriptionVerificationImageHashLock.class)));
        given(verificationRepository.findFirstPreviousByImageSha256(
                current.getImageSha256(), current.getCreatedAt(), current.getVerificationId()))
                .willReturn(Optional.ofNullable(previous));

        service.detectAndRecord(current, NOW);

        ArgumentCaptor<SubscriptionVerificationImageReuse> captor =
                ArgumentCaptor.forClass(SubscriptionVerificationImageReuse.class);
        then(imageReuseRepository).should().save(captor.capture());
        assertThat(captor.getValue().getReuseType()).isEqualTo(expectedType);
        assertThat(captor.getValue().getMatchedVerificationId())
                .isEqualTo(previous == null ? null : previous.getVerificationId());
        assertThat(captor.getValue().getDetectedAt()).isEqualTo(NOW);
        then(hashLockRepository).should().insertIgnore(current.getImageSha256(), NOW);
    }

    /** 최초·동일 사용자 동일/다른 Creator·다른 사용자 케이스를 제공한다. */
    private static Object[][] reuseCases() {
        SubscriptionVerification first = verification(100L, 7L, 42L);
        return new Object[][] {
                {first, null, SubscriptionVerificationImageReuseType.FIRST_USE},
                {verification(101L, 7L, 42L), first,
                        SubscriptionVerificationImageReuseType.SAME_MEMBER_SAME_CREATOR},
                {verification(102L, 7L, 43L), first,
                        SubscriptionVerificationImageReuseType.SAME_MEMBER_DIFFERENT_CREATOR},
                {verification(103L, 8L, 42L), first,
                        SubscriptionVerificationImageReuseType.DIFFERENT_MEMBER}
        };
    }

    /** 테스트용 Verification에 동일 hash와 필요한 식별자를 부여한다. */
    private static SubscriptionVerification verification(Long id, Long memberId, Long creatorId) {
        SubscriptionVerification verification = SubscriptionVerification.pending(
                memberId, creatorId, 103L, UUID.randomUUID().toString(), "a".repeat(64),
                "채널", "@channel", "object-key", "b".repeat(64), "JPEG_V1",
                UUID.randomUUID().toString(), NOW);
        ReflectionTestUtils.setField(verification, "verificationId", id);
        return verification;
    }
}
