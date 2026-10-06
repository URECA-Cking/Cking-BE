package kr.co.cking.abuse.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

        recorder.record(1L, RESULT);

        verify(persistenceService, never()).save(any(), any());
        verify(cooldownStore, never()).release(any());
    }

    /** 저장 성공 후에는 TTL 동안 중복을 막도록 Lease를 유지한다. */
    @Test
    void 저장에_성공하면_Cooldown을_유지한다() {
        when(cooldownStore.tryAcquire(RESULT.abuseType(), RESULT.cooldownScopeHash(), Duration.ofSeconds(20)))
                .thenReturn(Optional.of(lease));
        when(persistenceService.save(1L, RESULT)).thenReturn(AbuseDetection.detected(1L, RESULT));

        recorder.record(1L, RESULT);

        verify(persistenceService).save(1L, RESULT);
        verify(cooldownStore, never()).release(any());
    }

    /** 독립 저장의 시작·flush·commit 오류가 발생하면 Lease를 best-effort로 해제하고 오류를 전파한다. */
    @Test
    void DB_저장에_실패하면_Cooldown을_해제하고_오류를_전파한다() {
        when(cooldownStore.tryAcquire(RESULT.abuseType(), RESULT.cooldownScopeHash(), Duration.ofSeconds(20)))
                .thenReturn(Optional.of(lease));
        IllegalStateException failure = new IllegalStateException("DB 저장 실패");
        when(persistenceService.save(1L, RESULT)).thenThrow(failure);

        assertThatThrownBy(() -> recorder.record(1L, RESULT)).isSameAs(failure);

        verify(cooldownStore).release(lease);
    }

    /** Cooldown 해제까지 실패해도 원래 DB 저장 실패가 호출자에게 그대로 전파된다. */
    @Test
    void Cooldown_해제에_실패해도_원래_DB_저장_오류를_유지한다() {
        when(cooldownStore.tryAcquire(RESULT.abuseType(), RESULT.cooldownScopeHash(), Duration.ofSeconds(20)))
                .thenReturn(Optional.of(lease));
        IllegalStateException persistenceFailure = new IllegalStateException("DB 저장 실패");
        when(persistenceService.save(1L, RESULT)).thenThrow(persistenceFailure);
        when(cooldownStore.release(lease)).thenThrow(new IllegalStateException("Redis 연결 실패"));

        assertThatThrownBy(() -> recorder.record(1L, RESULT)).isSameAs(persistenceFailure);

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
