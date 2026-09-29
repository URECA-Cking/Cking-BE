package kr.co.cking.subscriptionverification.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

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
}
