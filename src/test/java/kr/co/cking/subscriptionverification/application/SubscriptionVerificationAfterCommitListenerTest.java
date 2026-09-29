package kr.co.cking.subscriptionverification.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import kr.co.cking.common.storage.InMemoryObjectStorage;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerification;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class SubscriptionVerificationAfterCommitListenerTest {

    @Test
    void Processor가_구성되면_Commit_이벤트를_Trigger에_전달한다() {
        SubscriptionVerificationProcessingTrigger trigger =
                mock(SubscriptionVerificationProcessingTrigger.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SubscriptionVerificationProcessingTrigger> provider =
                mock(ObjectProvider.class);
        org.mockito.BDDMockito.given(provider.getIfAvailable()).willReturn(trigger);
        SubscriptionVerificationAfterCommitListener listener =
                new SubscriptionVerificationAfterCommitListener(provider);

        listener.onSubmitted(new SubscriptionVerificationSubmittedEvent(123L));

        then(trigger).should().trigger(123L);
    }

    @Test
    void Processor가_아직_없어도_제출_Commit은_실패하지_않는다() {
        @SuppressWarnings("unchecked")
        ObjectProvider<SubscriptionVerificationProcessingTrigger> provider =
                mock(ObjectProvider.class);
        SubscriptionVerificationAfterCommitListener listener =
                new SubscriptionVerificationAfterCommitListener(provider);

        listener.onSubmitted(new SubscriptionVerificationSubmittedEvent(123L));

        then(provider).should().getIfAvailable();
    }

    @Test
    void Trigger가_실패해도_AFTER_COMMIT_예외를_호출자에게_전파하지_않는다() {
        SubscriptionVerificationProcessingTrigger trigger =
                mock(SubscriptionVerificationProcessingTrigger.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SubscriptionVerificationProcessingTrigger> provider =
                mock(ObjectProvider.class);
        org.mockito.BDDMockito.given(provider.getIfAvailable()).willReturn(trigger);
        willThrow(new IllegalStateException("queue rejected")).given(trigger).trigger(123L);
        SubscriptionVerificationAfterCommitListener listener =
                new SubscriptionVerificationAfterCommitListener(provider);

        assertThatCode(() -> listener.onSubmitted(new SubscriptionVerificationSubmittedEvent(123L)))
                .doesNotThrowAnyException();

        then(trigger).should().trigger(123L);
    }

    /** Trigger 제출이 거절돼도 이미 commit된 PENDING 상태와 업로드 Object를 보존하는지 검증한다. */
    @Test
    void Trigger_실패_뒤에도_PENDING과_Object를_유지한다() {
        SubscriptionVerificationProcessingTrigger trigger =
                mock(SubscriptionVerificationProcessingTrigger.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SubscriptionVerificationProcessingTrigger> provider =
                mock(ObjectProvider.class);
        org.mockito.BDDMockito.given(provider.getIfAvailable()).willReturn(trigger);
        willThrow(new IllegalStateException("queue rejected")).given(trigger).trigger(123L);
        SubscriptionVerificationAfterCommitListener listener =
                new SubscriptionVerificationAfterCommitListener(provider);
        String objectKey = "subscription-verifications/2026/09/id/image.jpg";
        byte[] image = "normalized-image".getBytes(StandardCharsets.UTF_8);
        InMemoryObjectStorage objectStorage = new InMemoryObjectStorage(Duration.ofMinutes(5));
        objectStorage.put(objectKey, image, "image/jpeg");
        SubscriptionVerification verification = SubscriptionVerification.pending(
                7L,
                42L,
                103L,
                UUID.randomUUID().toString(),
                "a".repeat(64),
                "채널",
                "@channel",
                objectKey,
                "b".repeat(64),
                "JPEG_V1",
                UUID.randomUUID().toString(),
                Instant.parse("2026-09-29T00:00:00Z"));

        listener.onSubmitted(new SubscriptionVerificationSubmittedEvent(123L));

        assertThat(verification.getStatus()).isEqualTo(SubscriptionVerificationStatus.PENDING);
        assertThat(objectStorage.get(objectKey)).isEqualTo(image);
    }
}
