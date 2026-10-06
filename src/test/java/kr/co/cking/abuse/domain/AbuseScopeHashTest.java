package kr.co.cking.abuse.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Abuse Redis Key와 DetectionResult가 공유하는 Scope Hash 계약을 검증한다. */
class AbuseScopeHashTest {

    /** ASCII canonical value는 공개된 SHA-256 lowercase hex 결과와 같아야 한다. */
    @Test
    void canonicalValue를_SHA256_lowercase_hex로_변환한다() {
        assertThat(AbuseScopeHash.fromCanonicalValue("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    /** 같은 UTF-8 canonical value는 반복 호출에도 같은 hash를 반환해야 한다. */
    @Test
    void 같은_canonicalValue는_항상_같은_hash를_반환한다() {
        String canonicalValue = "MISSION:CREATOR:DAILY:회원:10:3:2026-10-01";

        assertThat(AbuseScopeHash.fromCanonicalValue(canonicalValue))
                .isEqualTo(AbuseScopeHash.fromCanonicalValue(canonicalValue))
                .matches("^[0-9a-f]{64}$");
    }

    /** 서로 다른 canonical scope는 같은 Redis Key hash로 합쳐지지 않아야 한다. */
    @Test
    void 서로_다른_scope는_서로_다른_hash를_반환한다() {
        String missionBusinessKeyHash = AbuseScopeHash.fromCanonicalValue(
                "MISSION:CREATOR:DAILY:1:10:3:2026-10-01");
        String cooldownScopeHash = AbuseScopeHash.fromCanonicalValue("USER:1");

        assertThat(missionBusinessKeyHash).isNotEqualTo(cooldownScopeHash);
    }

    /** null 또는 blank canonical value는 개인 식별자가 없는 Redis Key 생성을 막기 위해 거부한다. */
    @Test
    void null과_blank_canonicalValue를_거부한다() {
        assertThatThrownBy(() -> AbuseScopeHash.fromCanonicalValue(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AbuseScopeHash.fromCanonicalValue("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** DetectionResult도 공통 Scope Hash 구현이 만든 결과를 그대로 계약 값으로 사용한다. */
    @Test
    void DetectionResult는_공통_ScopeHash_계약을_사용한다() {
        String scopeHash = AbuseScopeHash.fromCanonicalValue("USER:1");

        DetectionResult result = new DetectionResult(
                AbuseType.MISSION_REQUEST_BURST,
                scopeHash,
                java.time.Instant.parse("2026-10-01T00:00:00Z"),
                AbuseTestFixtures.userEvidence());

        assertThat(result.cooldownScopeHash()).isEqualTo(scopeHash);
    }
}
