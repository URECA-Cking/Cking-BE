package kr.co.cking.subscriptionverification.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** PENDING 저장이 실제 Commit된 뒤에만 선택적으로 비동기 처리 Trigger를 호출한다. */
@Component
@RequiredArgsConstructor
@Slf4j
class SubscriptionVerificationAfterCommitListener {

    private final ObjectProvider<SubscriptionVerificationProcessingTrigger> triggerProvider;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubmitted(SubscriptionVerificationSubmittedEvent event) {
        SubscriptionVerificationProcessingTrigger trigger = triggerProvider.getIfAvailable();
        if (trigger == null) {
            log.debug("구독 인증 Processor가 아직 구성되지 않았습니다. verificationId={}", event.verificationId());
            return;
        }
        trigger.trigger(event.verificationId());
    }
}
