package kr.co.cking.abuse.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import kr.co.cking.abuse.application.model.AbuseDetectionSearchCondition;
import kr.co.cking.abuse.domain.AbuseDetection;
import kr.co.cking.abuse.domain.AbuseScopeHash;
import kr.co.cking.abuse.domain.AbuseTestFixtures;
import kr.co.cking.abuse.domain.AbuseType;
import kr.co.cking.abuse.domain.DetectionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageRequest;

/** Abuse Detection 영속성 Adapter가 DB·관리자 조회 Repository 오류를 숨기지 않는지 단위 검증한다. */
class AbuseDetectionPersistenceAdapterFailureTest {

    private AbuseDetectionJpaRepository jpaRepository;
    private AbuseDetectionPersistenceAdapter adapter;

    /** 각 테스트가 독립된 JPA Repository mock을 사용하도록 Adapter를 초기화한다. */
    @BeforeEach
    void setUp() {
        jpaRepository = mock(AbuseDetectionJpaRepository.class);
        adapter = new AbuseDetectionPersistenceAdapter(jpaRepository, mock(AbuseDetectionEvidenceJsonMapper.class));
    }

    /** Detection 저장 DB 오류는 저장 성공 객체로 위장하지 않고 원래 오류를 전파한다. */
    @Test
    void Detection_DB_저장_오류를_저장_성공으로_숨기지_않는다() {
        AbuseDetection detection = detection();
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("Detection DB 저장 오류");
        when(jpaRepository.saveAndFlush(org.mockito.ArgumentMatchers.any(AbuseDetectionJpaEntity.class)))
                .thenThrow(failure);

        assertThatThrownBy(() -> adapter.save(detection))
                .isSameAs(failure);
    }

    /** 관리자 Detection 목록 조회 Repository 오류는 빈 목록으로 위장하지 않고 원래 오류를 전파한다. */
    @Test
    void 관리자_Repository_오류를_빈_목록으로_숨기지_않는다() {
        AbuseDetectionSearchCondition condition = new AbuseDetectionSearchCondition(null, null, null, null, null);
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("관리자 Repository 오류");
        when(jpaRepository.search(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenThrow(failure);

        assertThatThrownBy(() -> adapter.search(condition, PageRequest.of(0, 20)))
                .isSameAs(failure);
    }

    /** 저장 오류 검증에 사용할 아직 영속화되지 않은 Detection Aggregate를 생성한다. */
    private AbuseDetection detection() {
        return AbuseDetection.detected(17L, new DetectionResult(
                AbuseType.MISSION_REQUEST_BURST,
                AbuseScopeHash.fromCanonicalValue("USER:17"),
                Instant.parse("2026-10-06T08:00:00Z"),
                AbuseTestFixtures.userEvidence()));
    }
}
