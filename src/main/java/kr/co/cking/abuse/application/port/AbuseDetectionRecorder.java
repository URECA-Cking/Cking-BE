package kr.co.cking.abuse.application.port;

import kr.co.cking.abuse.domain.DetectionResult;

/** Rule Engine이 확정한 Detection을 중복 제어 후 영속화하는 저장 경계다. */
public interface AbuseDetectionRecorder {

    /** Cooldown Lease를 획득한 Detection만 독립 트랜잭션으로 저장한다. */
    void record(DetectionResult result);
}
