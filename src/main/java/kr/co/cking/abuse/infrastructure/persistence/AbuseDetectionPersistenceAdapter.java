package kr.co.cking.abuse.infrastructure.persistence;

import static kr.co.cking.common.validation.DomainValidator.requirePositive;

import kr.co.cking.abuse.application.model.AbuseDetectionSearchCondition;
import kr.co.cking.abuse.application.port.AbuseDetectionRepository;
import kr.co.cking.abuse.domain.AbuseDetection;
import kr.co.cking.abuse.domain.AbuseReviewDecision;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** AbuseDetectionRepository Port를 MySQL과 Spring Data JPA로 구현한다. */
@Repository
class AbuseDetectionPersistenceAdapter implements AbuseDetectionRepository {

    private static final Sort DETECTION_ORDER = Sort.by(
            Sort.Order.desc("detectedAt"),
            Sort.Order.desc("id"));

    private final AbuseDetectionJpaRepository jpaRepository;
    private final AbuseDetectionEvidenceJsonMapper evidenceJsonMapper;

    /** JPA 저장소와 Evidence JSON 매퍼를 주입받아 Domain과 영속성 경계를 분리한다. */
    AbuseDetectionPersistenceAdapter(
            AbuseDetectionJpaRepository jpaRepository,
            AbuseDetectionEvidenceJsonMapper evidenceJsonMapper
    ) {
        this.jpaRepository = jpaRepository;
        this.evidenceJsonMapper = evidenceJsonMapper;
    }

    /** Detection을 즉시 flush해 생성 ID가 반영된 순수 Domain 객체를 반환한다. */
    @Override
    @Transactional
    public AbuseDetection save(AbuseDetection detection) {
        if (detection.detectionId() != null) {
            throw new IllegalArgumentException("이미 영속화된 Detection은 save할 수 없습니다.");
        }
        AbuseDetectionJpaEntity entity = AbuseDetectionJpaEntity.create(
                detection.memberId(),
                detection.abuseType(),
                detection.status(),
                detection.detectedAt(),
                detection.reviewedAt(),
                detection.reviewedBy(),
                evidenceJsonMapper.toJson(detection.evidence()));
        return toDomain(jpaRepository.saveAndFlush(entity));
    }

    /** Detection ID로 한 건을 찾아 순수 Domain 객체로 복원한다. */
    @Override
    @Transactional(readOnly = true)
    public Optional<AbuseDetection> findById(Long detectionId) {
        return jpaRepository.findById(detectionId).map(this::toDomain);
    }

    /** 선택 조건으로 Detection을 조회하고 관리자 API 계약의 최신순 정렬을 강제한다. */
    @Override
    @Transactional(readOnly = true)
    public Page<AbuseDetection> search(AbuseDetectionSearchCondition condition, Pageable pageable) {
        Pageable detectedAtDescending = PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(), DETECTION_ORDER);
        return jpaRepository.search(
                condition.memberId(),
                condition.abuseType(),
                condition.status(),
                detectedAtDescending).map(this::toDomain);
    }

    /** DETECTED 상태의 행만 목표 검토 상태로 조건부 전이하고 영향 행 수를 반환한다. */
    @Override
    @Transactional
    public int reviewIfDetected(
            Long detectionId,
            AbuseReviewDecision decision,
            Long reviewedBy,
            Instant reviewedAt
    ) {
        requirePositive(detectionId, "detectionId");
        Objects.requireNonNull(decision, "decision은 필수입니다.");
        requirePositive(reviewedBy, "reviewedBy");
        Objects.requireNonNull(reviewedAt, "reviewedAt은 필수입니다.");
        return jpaRepository.reviewIfDetected(
                detectionId,
                decision.toStatus(),
                reviewedBy,
                reviewedAt);
    }

    /** JPA Entity의 저장 상태와 JSON Evidence를 순수 Domain Aggregate로 복원한다. */
    private AbuseDetection toDomain(AbuseDetectionJpaEntity entity) {
        return AbuseDetection.restore(
                entity.id(),
                entity.memberId(),
                entity.abuseType(),
                entity.status(),
                entity.detectedAt(),
                entity.reviewedAt(),
                entity.reviewedBy(),
                evidenceJsonMapper.fromJson(entity.evidenceJson()));
    }
}
