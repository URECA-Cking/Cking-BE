package kr.co.cking.abuse.config;

import kr.co.cking.abuse.application.AbuseObservationExecutor;
import kr.co.cking.abuse.application.port.AbuseDetectionRecorder;
import kr.co.cking.abuse.application.rule.AbuseRuleEvaluator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Configuration;

/** 구현체와 무관하게 Abuse Detection 설정 계약을 애플리케이션에 등록한다. */
@Configuration
@EnableConfigurationProperties(AbuseProperties.class)
public class AbuseConfiguration {

    /** 비활성 상태는 저장 구현 없이 기동하고, 활성 상태에서는 저장 Port를 필수로 요구한다. */
    @Bean
    @ConditionalOnMissingBean(AbuseObservationExecutor.class)
    public AbuseObservationExecutor abuseObservationExecutor(
            AbuseProperties properties,
            AbuseRuleEvaluator ruleEvaluator,
            ObjectProvider<AbuseDetectionRecorder> recorderProvider
    ) {
        AbuseDetectionRecorder recorder = properties.enabled()
                ? recorderProvider.getObject()
                : (memberId, result) -> { throw new IllegalStateException("비활성 Abuse 저장 Port가 호출됐습니다."); };
        return new AbuseObservationExecutor(properties, ruleEvaluator, recorder);
    }
}
