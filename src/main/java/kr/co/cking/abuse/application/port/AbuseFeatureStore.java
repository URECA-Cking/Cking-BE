package kr.co.cking.abuse.application.port;

import kr.co.cking.abuse.application.model.AbuseFeatureSnapshot;
import kr.co.cking.abuse.application.model.AbuseFeatureWindowPolicy;
import kr.co.cking.abuse.domain.AbuseObservationEvent;

/** 실시간 Feature를 원자 갱신하고 갱신 직후 값을 반환하는 저장소 Port다. */
public interface AbuseFeatureStore {

    /**
     * Observation에 필요한 sliding count·sequence·최근 EARN 상태를 원자 갱신한다.
     * Threshold 비교와 Detection 생성은 수행하지 않으며, 장애를 정상 Snapshot으로 숨기지 않는다.
     */
    AbuseFeatureSnapshot record(
            AbuseObservationEvent observation,
            AbuseFeatureWindowPolicy windowPolicy
    );
}
