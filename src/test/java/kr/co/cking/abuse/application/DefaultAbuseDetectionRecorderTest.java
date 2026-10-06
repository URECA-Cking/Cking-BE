package kr.co.cking.abuse.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import kr.co.cking.abuse.application.model.CooldownLease;
import kr.co.cking.abuse.application.port.AbuseCooldownStore;
import kr.co.cking.abuse.config.AbuseProperties;
import kr.co.cking.abuse.domain.AbuseDetection;
import kr.co.cking.abuse.domain.AbuseScopeHash;
import kr.co.cking.abuse.domain.AbuseTestFixtures;
import kr.co.cking.abuse.domain.AbuseType;
import kr.co.cking.abuse.domain.DetectionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** DefaultAbuseDetectionRecorder의 Cooldown·저장 실패 경계를 단위 검증한다. */
@ExtendWith(MockitoExtension.class)
class DefaultAbuseDetectionRecorderTest {

    private static final DetectionResult RESULT = new DetectionResult(
            1L,
            AbuseType.MISSION_REQUEST_BURST,
            AbuseScopeHash.fromCanonicalValue("USER:1"),
            Instant.parse("2026-10-06T00:00:00Z"),
            AbuseTestFixtures.userEvidence());

    @Mock
    private AbuseCooldownStore cooldownStore;

    @Mock
    private AbuseDetectionPersistenceService persistenceService;

    private DefaultAbuseDetectionRecorder recorder;
    private CooldownLease lease;

    /** 매 테스트에 활성화된 설정과 고유 Lease를 준비한다. */
    @BeforeEach
    void setUp() {
        lease = new CooldownLease(RESULT.abuseType(), RESULT.cooldownScopeHash(), UUID.randomUUID());
        recorder = new DefaultAbuseDetectionRecorder(cooldownStore, properties(), persistenceService);
    }

    /** 이미 Lease가 있으면 같은 Detection을 저장하지 않는다. */
    @Test
    void Cooldown을_획득하지_못하면_INSERT를_생략한다() {
        when(cooldownStore.tryAcquire(RESULT.abuseType(), RESULT.cooldownScopeHash(), Duration.ofSeconds(20)))
                .thenReturn(Optional.empty());

        recorder.record(RESULT);

        verify(persistenceService, never()).save(any());
        verify(cooldownStore, never()).release(any());
    }

    /** 저장 성공 후에는 TTL 동안 중복을 막도록 Lease를 유지한다. */
    @Test
    void 저장에_성공하면_Cooldown을_유지한다() {
        when(cooldownStore.tryAcquire(RESULT.abuseType(), RESULT.cooldownScopeHash(), Duration.ofSeconds(20)))
                .thenReturn(Optional.of(lease));
        when(persistenceService.save(RESULT)).thenReturn(AbuseDetection.detected(RESULT));

        recorder.record(RESULT);

        verify(persistenceService).save(RESULT);
        verify(cooldownStore, never()).release(any());
    }

    /** 독립 저장의 시작·flush·commit 오류가 발생하면 Lease를 best-effort로 해제한다. */
    @Test
    void DB_저장에_실패하면_Cooldown을_해제하고_오류를_전파하지_않는다() {
        when(cooldownStore.tryAcquire(RESULT.abuseType(), RESULT.cooldownScopeHash(), Duration.ofSeconds(20)))
                .thenReturn(Optional.of(lease));
        when(persistenceService.save(RESULT)).thenThrow(new IllegalStateException("DB 저장 실패"));

        assertThatCode(() -> recorder.record(RESULT)).doesNotThrowAnyException();

        verify(cooldownStore).release(lease);
    }

    /** 활성화된 Rule 설정으로 Cooldown TTL을 계산한다. */
    private AbuseProperties properties() {
        return new AbuseProperties(true,
                new AbuseProperties.CountRule(Duration.ofSeconds(10), 3),
                new AbuseProperties.CountRule(Duration.ofSeconds(20), 2),
                new AbuseProperties.CountRule(Duration.ofSeconds(30), 4),
                new AbuseProperties.ConsecutiveRule(Duration.ofSeconds(40), 2, 2),
                new AbuseProperties.RotationRule(Duration.ofSeconds(50), 3),
                new AbuseProperties.RapidRule(Duration.ofSeconds(2), Duration.ofSeconds(60), 2),
                new AbuseProperties.FailureRule(Duration.ofSeconds(70), 2, 2, 2));
    }
}
