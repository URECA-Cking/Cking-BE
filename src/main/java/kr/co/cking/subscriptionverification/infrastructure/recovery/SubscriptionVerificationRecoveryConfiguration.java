package kr.co.cking.subscriptionverification.infrastructure.recovery;

import java.time.Duration;
import kr.co.cking.subscriptionverification.infrastructure.async.SubscriptionVerificationProcessingExecutorProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 구독 인증 Recovery 설정을 검증해 무제한 조회·제출을 막는다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SubscriptionVerificationRecoveryProperties.class)
public class SubscriptionVerificationRecoveryConfiguration {

    @Bean
    SubscriptionVerificationRecoverySettingsValidator subscriptionVerificationRecoverySettingsValidator(
            SubscriptionVerificationRecoveryProperties recovery,
            SubscriptionVerificationProcessingExecutorProperties executor) {
        validate(recovery, executor);
        return new SubscriptionVerificationRecoverySettingsValidator();
    }

    private void validate(
            SubscriptionVerificationRecoveryProperties recovery,
            SubscriptionVerificationProcessingExecutorProperties executor) {
        requirePositive(recovery.getPendingGracePeriod(), "Recovery pending grace period");
        requirePositive(recovery.getRewardBackoffBase(), "Reward backoff base");
        requirePositive(recovery.getRewardBackoffMax(), "Reward backoff max");
        if (recovery.getRewardBackoffBase().compareTo(recovery.getRewardBackoffMax()) > 0) {
            throw new IllegalArgumentException("Reward backoff base는 max보다 클 수 없습니다.");
        }
        if (recovery.getIntervalMs() <= 0) {
            throw new IllegalArgumentException("Recovery interval은 양수여야 합니다.");
        }
        if (recovery.getBatchSize() <= 0
                || recovery.getBatchSize() > executor.getMaxPoolSize() + executor.getQueueCapacity()) {
            throw new IllegalArgumentException("Recovery batch size는 Executor 수용량 이내의 양수여야 합니다.");
        }
        if (recovery.getMaxProcessingAttempts() <= 0) {
            throw new IllegalArgumentException("Processing 최대 시도 횟수는 양수여야 합니다.");
        }
    }

    private void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + "은 양수여야 합니다.");
        }
    }

    /** 설정 검증 Bean의 명시적인 타입이다. */
    static final class SubscriptionVerificationRecoverySettingsValidator {
    }
}
