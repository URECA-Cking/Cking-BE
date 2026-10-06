package kr.co.cking.abuse.application;

import kr.co.cking.abuse.application.model.CooldownLease;
import kr.co.cking.abuse.application.port.AbuseCooldownStore;
import kr.co.cking.abuse.application.port.AbuseDetectionRecorder;
import kr.co.cking.abuse.config.AbuseProperties;
import kr.co.cking.abuse.domain.AbuseDetection;
import kr.co.cking.abuse.domain.DetectionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/** Cooldown 소유권으로 동일 Detection 저장을 제한하고 독립 트랜잭션 저장을 조합한다. */
@Slf4j
@Service
public class DefaultAbuseDetectionRecorder implements AbuseDetectionRecorder {

    private final AbuseCooldownStore cooldownStore;
    private final AbuseProperties properties;
    private final AbuseDetectionPersistenceService persistenceService;

    /** Cooldown·설정·독립 저장 서비스를 주입받아 Detection 저장 흐름을 구성한다. */
    public DefaultAbuseDetectionRecorder(
            AbuseCooldownStore cooldownStore,
            AbuseProperties properties,
            AbuseDetectionPersistenceService persistenceService
    ) {
        this.cooldownStore = Objects.requireNonNull(cooldownStore, "cooldownStore는 필수입니다.");
        this.properties = Objects.requireNonNull(properties, "properties는 필수입니다.");
        this.persistenceService = Objects.requireNonNull(persistenceService, "persistenceService는 필수입니다.");
    }

    /** Cooldown을 얻은 Detection만 저장하고 저장 실패 시 해당 Lease를 최선으로 해제한다. */
    @Override
    public void record(DetectionResult result) {
        Objects.requireNonNull(result, "result는 필수입니다.");
        Optional<CooldownLease> lease = cooldownStore.tryAcquire(
                result.abuseType(), result.cooldownScopeHash(), properties.cooldownTtl(result.abuseType()));
        if (lease.isEmpty()) {
            return;
        }

        try {
            AbuseDetection saved = persistenceService.save(result);
            log.info("[ABUSE_DETECTED] detectionId={}, userId={}, abuseType={}, matchedRules={}, detectedAt={}",
                    saved.detectionId(), saved.memberId(), saved.abuseType(), saved.evidence().matchedRules(),
                    saved.detectedAt());
        } catch (RuntimeException exception) {
            log.error("Abuse Detection 저장에 실패했습니다. userId={}, abuseType={}, detectedAt={}",
                    result.memberId(), result.abuseType(), result.detectedAt(), exception);
            releaseAfterPersistenceFailure(lease.orElseThrow());
        }
    }

    /** DB 저장에 실패한 Lease를 소유 Token 조건으로 해제하되 해제 실패는 원래 오류를 가리지 않는다. */
    private void releaseAfterPersistenceFailure(CooldownLease lease) {
        try {
            cooldownStore.release(lease);
        } catch (RuntimeException exception) {
            log.warn("Abuse Detection 저장 실패 후 Cooldown 해제에 실패했습니다. abuseType={}, scopeHash={}",
                    lease.abuseType(), lease.scopeHash(), exception);
        }
    }
}
