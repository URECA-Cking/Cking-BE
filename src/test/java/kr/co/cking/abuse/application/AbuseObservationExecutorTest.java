package kr.co.cking.abuse.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.co.cking.abuse.application.port.AbuseDetectionRecorder;
import kr.co.cking.abuse.application.rule.AbuseRuleEvaluator;
import kr.co.cking.abuse.config.AbuseProperties;
import kr.co.cking.abuse.domain.AbuseActionType;
import kr.co.cking.abuse.domain.AbuseObservationEvent;
import kr.co.cking.abuse.domain.AbuseScopeHash;
import kr.co.cking.abuse.domain.AbuseTestFixtures;
import kr.co.cking.abuse.domain.AbuseType;
import kr.co.cking.abuse.domain.BalanceScope;
import kr.co.cking.abuse.domain.DetectionResult;
import kr.co.cking.abuse.domain.ResultClassification;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AbuseObservationExecutorTest {

    private final AbuseProperties properties = mock(AbuseProperties.class);
    private final AbuseRuleEvaluator ruleEvaluator = mock(AbuseRuleEvaluator.class);
    private final AbuseDetectionRecorder detectionRecorder = mock(AbuseDetectionRecorder.class);
    private AbuseObservationExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new AbuseObservationExecutor(properties, ruleEvaluator, detectionRecorder);
    }

    @Test
    void 비활성_설정은_Rule과_저장을_호출하지_않는다() {
        executor.observe(observation());

        verifyNoInteractions(ruleEvaluator, detectionRecorder);
    }

    @Test
    void 평가된_Detection을_원래_순서대로_저장_Port에_전달한다() {
        when(properties.enabled()).thenReturn(true);
        AbuseObservationEvent observation = observation();
        DetectionResult first = result(AbuseType.MISSION_REQUEST_BURST);
        DetectionResult second = result(AbuseType.FAILURE_BURST);
        when(ruleEvaluator.evaluate(observation)).thenReturn(List.of(first, second));

        executor.observe(observation);

        var order = inOrder(ruleEvaluator, detectionRecorder);
        order.verify(ruleEvaluator).evaluate(observation);
        order.verify(detectionRecorder).record(observation.userId(), first);
        order.verify(detectionRecorder).record(observation.userId(), second);
        order.verifyNoMoreInteractions();
    }

    @Test
    void Feature_Store나_Rule_평가_실패는_원_업무로_전파하지_않는다() {
        when(properties.enabled()).thenReturn(true);
        AbuseObservationEvent observation = observation();
        when(ruleEvaluator.evaluate(observation)).thenThrow(new IllegalStateException("Redis failure"));

        assertThatCode(() -> executor.observe(observation)).doesNotThrowAnyException();
        verifyNoInteractions(detectionRecorder);
    }

    @Test
    void 저장_Transaction_실패도_원_업무로_전파하지_않는다() {
        when(properties.enabled()).thenReturn(true);
        AbuseObservationEvent observation = observation();
        DetectionResult result = result(AbuseType.MISSION_REQUEST_BURST);
        when(ruleEvaluator.evaluate(observation)).thenReturn(List.of(result));
        org.mockito.Mockito.doThrow(new IllegalStateException("DB commit failure"))
                .when(detectionRecorder).record(observation.userId(), result);

        assertThatCode(() -> executor.observe(observation)).doesNotThrowAnyException();
    }

    @Test
    void 원래_BusinessException을_관찰_실패가_대체하지_않는다() {
        when(properties.enabled()).thenReturn(true);
        AbuseObservationEvent observation = observation();
        when(ruleEvaluator.evaluate(observation)).thenThrow(new IllegalStateException("Rule failure"));
        BusinessException original = new BusinessException(CommonErrorCode.FORBIDDEN);

        assertThatThrownBy(() -> {
            try {
                throw original;
            } catch (BusinessException businessFailure) {
                executor.observe(observation);
                throw businessFailure;
            }
        }).isSameAs(original);
    }

    private AbuseObservationEvent observation() {
        Instant observedAt = Instant.parse("2026-10-06T08:00:00Z");
        return new AbuseObservationEvent(
                UUID.randomUUID(), 17L, AbuseActionType.MISSION_COMPLETE, UUID.randomUUID(),
                "DUPLICATE_MISSION", ResultClassification.BUSINESS_FAILURE,
                5L, null, 103L, "2026-10-06", BalanceScope.creator(5L),
                "MISSION:CREATOR:DAILY:17:5:103:2026-10-06", observedAt.minusMillis(20), observedAt);
    }

    private DetectionResult result(AbuseType type) {
        return new DetectionResult(type, AbuseScopeHash.fromCanonicalValue("USER:17"),
                Instant.parse("2026-10-06T08:00:00Z"), AbuseTestFixtures.userEvidence());
    }
}
