package kr.co.cking.abuse.application;

import java.util.List;
import java.util.Objects;
import kr.co.cking.abuse.application.port.AbuseDetectionRecorder;
import kr.co.cking.abuse.application.rule.AbuseRuleEvaluator;
import kr.co.cking.abuse.config.AbuseProperties;
import kr.co.cking.abuse.domain.AbuseObservationEvent;
import kr.co.cking.abuse.domain.DetectionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 원 업무 결과가 확정된 뒤 Abuse 평가·저장 오류를 격리하는 Observation 경계다. */
@Slf4j
@Component
public final class AbuseObservationExecutor {

    private final AbuseProperties properties;
    private final AbuseRuleEvaluator ruleEvaluator;
    private final AbuseDetectionRecorder detectionRecorder;

    /** Detection 저장 구현이 제공되면 이 경계를 조립한다. */
    public AbuseObservationExecutor(
            AbuseProperties properties,
            AbuseRuleEvaluator ruleEvaluator,
            AbuseDetectionRecorder detectionRecorder
    ) {
        this.properties = Objects.requireNonNull(properties, "properties는 필수입니다.");
        this.ruleEvaluator = Objects.requireNonNull(ruleEvaluator, "ruleEvaluator는 필수입니다.");
        this.detectionRecorder = Objects.requireNonNull(detectionRecorder, "detectionRecorder는 필수입니다.");
    }

    /** 원 업무의 성공 응답이나 BusinessException을 결정한 뒤 호출하며 Abuse 장애를 전파하지 않는다. */
    public void observe(AbuseObservationEvent observation) {
        Objects.requireNonNull(observation, "observation은 필수입니다.");
        if (!properties.enabled()) {
            return;
        }

        List<DetectionResult> results;
        try {
            results = List.copyOf(ruleEvaluator.evaluate(observation));
        } catch (RuntimeException exception) {
            log.warn("[ABUSE_OBSERVATION_FAILED] stage=EVALUATION observationId={} actionType={} failureType={}",
                    observation.observationId(), observation.actionType(), exception.getClass().getName());
            return;
        }

        for (DetectionResult result : results) {
            try {
                detectionRecorder.record(observation.userId(), result);
            } catch (RuntimeException exception) {
                // 다른 Detection의 독립 저장 시도는 유지한다. 예외 메시지·stack trace는 기록하지 않는다.
                log.warn("[ABUSE_OBSERVATION_FAILED] stage=RECORD observationId={} actionType={} abuseType={} failureType={}",
                        observation.observationId(), observation.actionType(), result.abuseType(),
                        exception.getClass().getName());
            }
        }
    }
}
