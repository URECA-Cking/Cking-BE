package kr.co.cking.abuse.application;

import kr.co.cking.abuse.application.port.AbuseDetectionRepository;
import kr.co.cking.abuse.domain.AbuseDetection;
import kr.co.cking.abuse.domain.DetectionResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** Detection INSERT를 호출자의 업무 트랜잭션과 분리해 실행한다. */
@Service
public class AbuseDetectionPersistenceService {

    private final AbuseDetectionRepository detectionRepository;

    /** 기존 Detection Repository를 주입받아 Aggregate 생성과 실제 저장 책임을 분리한다. */
    public AbuseDetectionPersistenceService(AbuseDetectionRepository detectionRepository) {
        this.detectionRepository = Objects.requireNonNull(detectionRepository, "detectionRepository는 필수입니다.");
    }

    /** Detection Aggregate를 만들고 flush·commit까지 새 트랜잭션에서 완료한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AbuseDetection save(Long memberId, DetectionResult result) {
        Objects.requireNonNull(result, "result는 필수입니다.");
        return detectionRepository.save(AbuseDetection.detected(memberId, result));
    }
}
