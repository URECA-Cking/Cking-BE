package kr.co.cking.subscriptionverification.infrastructure.async;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 구독 인증 비동기 처리 자원과 Provider 동시 호출 한도를 외부 설정으로 분리한다. */
@ConfigurationProperties(prefix = "cking.verification.youtube-subscription.processing-executor")
public class SubscriptionVerificationProcessingExecutorProperties {

    private int corePoolSize = 2;
    private int maxPoolSize = 2;
    private int queueCapacity = 20;
    private int providerMaxConcurrentCalls = 2;
    private Duration processingLeaseDuration = Duration.ofSeconds(90);
    private int awaitTerminationSeconds = 30;

    /** 상시 유지할 Worker thread 수를 반환한다. */
    public int getCorePoolSize() {
        return corePoolSize;
    }

    /** 외부 설정에서 상시 유지할 Worker thread 수를 저장한다. */
    public void setCorePoolSize(int corePoolSize) {
        this.corePoolSize = corePoolSize;
    }

    /** queue 적체 시 확장할 최대 Worker thread 수를 반환한다. */
    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    /** 외부 설정에서 최대 Worker thread 수를 저장한다. */
    public void setMaxPoolSize(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    /** 메모리 폭주를 막기 위해 수용할 최대 대기 작업 수를 반환한다. */
    public int getQueueCapacity() {
        return queueCapacity;
    }

    /** 외부 설정에서 최대 대기 작업 수를 저장한다. */
    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    /** 한 인스턴스에서 동시에 실행할 Provider 호출 수를 반환한다. */
    public int getProviderMaxConcurrentCalls() {
        return providerMaxConcurrentCalls;
    }

    /** 외부 설정에서 Provider 동시 호출 수를 저장한다. */
    public void setProviderMaxConcurrentCalls(int providerMaxConcurrentCalls) {
        this.providerMaxConcurrentCalls = providerMaxConcurrentCalls;
    }

    /** Claim이 유효한 최대 기간을 반환한다. */
    public Duration getProcessingLeaseDuration() {
        return processingLeaseDuration;
    }

    /** 외부 설정에서 Claim lease 기간을 저장한다. */
    public void setProcessingLeaseDuration(Duration processingLeaseDuration) {
        this.processingLeaseDuration = processingLeaseDuration;
    }

    /** 종료 시 진행 중인 작업을 기다릴 최대 시간을 반환한다. */
    public int getAwaitTerminationSeconds() {
        return awaitTerminationSeconds;
    }

    /** 외부 설정에서 종료 대기 시간을 저장한다. */
    public void setAwaitTerminationSeconds(int awaitTerminationSeconds) {
        this.awaitTerminationSeconds = awaitTerminationSeconds;
    }
}
