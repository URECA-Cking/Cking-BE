package kr.co.cking.abuse.application.port;

import kr.co.cking.abuse.application.model.AbuseDetectionSearchCondition;
import kr.co.cking.abuse.domain.AbuseDetection;
import kr.co.cking.abuse.domain.AbuseReviewDecision;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Optional;

/** Detection 영속화와 관리자 조회·조건부 검토 전이의 저장소 Port다. */
public interface AbuseDetectionRepository {

    /** Detection을 flush까지 완료해 저장하고 식별자가 반영된 Domain 객체를 반환한다. */
    AbuseDetection save(AbuseDetection detection);

    Optional<AbuseDetection> findById(Long detectionId);

    /** REPEATABLE READ 스냅샷을 피하고 최신 검토 상태를 확인하도록 공유 잠금으로 조회한다. */
    Optional<AbuseDetection> findByIdForShare(Long detectionId);

    Page<AbuseDetection> search(AbuseDetectionSearchCondition condition, Pageable pageable);

    /** DETECTED 상태인 행만 조건부 전이하고 영향 행 수(0 또는 1)를 반환한다. */
    int reviewIfDetected(
            Long detectionId,
            AbuseReviewDecision decision,
            Long reviewedBy,
            Instant reviewedAt
    );
}
