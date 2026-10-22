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

    /** commit된 Verification만 Trigger에 넘기고 제출 결과를 바꾸지 않도록 실패를 격리한다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSubmitted(SubscriptionVerificationSubmittedEvent event) {
        try {
            SubscriptionVerificationProcessingTrigger trigger = triggerProvider.getIfAvailable();
            if (trigger == null) {
                log.debug(
                        "구독 인증 Processor가 아직 구성되지 않았습니다. verificationId={}",
                        event.verificationId());
                return;
            }
            trigger.trigger(event.verificationId());
        } catch (RuntimeException exception) {
            // DB Commit은 이미 완료됐다. 예외를 호출자에게 전파하면 제출 Service가
            // 정상 저장된 Verification의 Object를 보상 삭제하므로, Recovery가 다시 처리할 수 있게 격리한다.
            log.warn(
                    "구독 인증 AFTER_COMMIT Trigger 호출에 실패했습니다. Recovery가 다시 처리합니다. "
                            + "verificationId={}",
                    event.verificationId(),
                    exception);
        }
    }
}
