package kr.co.cking.abuse.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import kr.co.cking.abuse.application.model.AbuseDetectionSearchCondition;
import kr.co.cking.abuse.domain.AbuseCompositeRule;
import kr.co.cking.abuse.domain.AbuseDetection;
import kr.co.cking.abuse.domain.AbuseDetectionStatus;
import kr.co.cking.abuse.domain.AbuseMetric;
import kr.co.cking.abuse.domain.AbuseReviewDecision;
import kr.co.cking.abuse.domain.AbuseScopeHash;
import kr.co.cking.abuse.domain.AbuseSignal;
import kr.co.cking.abuse.domain.AbuseType;
import kr.co.cking.abuse.domain.DetectionEvidence;
import kr.co.cking.abuse.domain.DetectionResult;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/** MySQL 기반 AbuseDetectionRepository Adapter의 저장·조회·조건부 전이를 검증한다. */
@SpringBootTest
class AbuseDetectionPersistenceAdapterIntegrationTest {

    @Autowired
    private AbuseDetectionPersistenceAdapter adapter;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** save는 JSON Evidence를 flush하고 생성된 Detection ID를 가진 Domain 객체를 반환한다. */
    @Test
    void Detection을_JSON_Evidence와_함께_flush해_저장하고_ID를_반영한다() {
        Member member = saveMember("탐지 저장 회원");
        AbuseDetection saved = adapter.save(detection(
                member.getMemberId(), AbuseType.DUPLICATE_MISSION_BURST,
                Instant.parse("2026-10-01T00:00:00Z")));

        String evidenceJson = jdbcTemplate.queryForObject(
                "select evidence from abuse_detection where id = ?", String.class, saved.detectionId());

        assertThat(saved.detectionId()).isPositive();
        assertThat(evidenceJson).contains("ABUSE_V1", "duplicateMissionFailureCount", "RULE-01");
        assertThat(adapter.findById(saved.detectionId()))
                .hasValueSatisfying(found -> {
                    assertThat(found.memberId()).isEqualTo(member.getMemberId());
                    assertThat(found.evidence().scope().missionId()).isEqualTo(3L);
                    assertThat(found.evidence().signals()).containsExactly(AbuseSignal.REQUEST_ID_ROTATION);
                });
    }

    /** ID가 있는 Detection을 다시 save해 중복 행을 삽입하지 않는다. */
    @Test
    void 이미_저장된_Detection은_다시_save할_수_없다() {
        Member member = saveMember("중복 저장 검증 회원");
        AbuseDetection saved = adapter.save(detection(
                member.getMemberId(), AbuseType.MISSION_REQUEST_BURST,
                Instant.parse("2026-10-01T00:00:00Z")));

        assertThatThrownBy(() -> adapter.save(saved)).isInstanceOf(RuntimeException.class);

        Integer rowCount = jdbcTemplate.queryForObject(
                "select count(*) from abuse_detection where member_id = ?", Integer.class, member.getMemberId());
        assertThat(rowCount).isEqualTo(1);
    }

    /** save는 null Detection을 명확한 입력 검증 오류로 거부한다. */
    @Test
    void null_Detection은_저장할_수_없다() {
        assertThatThrownBy(() -> adapter.save(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("detection은 필수입니다.");
    }

    /** search는 선택 조건을 적용하고 호출자가 전달한 정렬과 관계없이 탐지 최신순으로 반환한다. */
    @Test
    void 조건으로_검색하고_detectedAt과_ID_내림차순으로_정렬한다() {
        Member member = saveMember("탐지 검색 회원");
        AbuseDetection oldest = adapter.save(detection(
                member.getMemberId(), AbuseType.MISSION_REQUEST_BURST,
                Instant.parse("2026-10-01T00:00:00Z")));
        AbuseDetection newest = adapter.save(detection(
                member.getMemberId(), AbuseType.FAILURE_BURST,
                Instant.parse("2026-10-01T00:01:00Z")));
        adapter.save(detection(
                saveMember("다른 탐지 회원").getMemberId(), AbuseType.MISSION_REQUEST_BURST,
                Instant.parse("2026-10-01T00:02:00Z")));

        var result = adapter.search(
                new AbuseDetectionSearchCondition(member.getMemberId(), null, AbuseDetectionStatus.DETECTED),
                PageRequest.of(0, 10));

        assertThat(result.getContent())
                .extracting(AbuseDetection::detectionId)
                .containsExactly(newest.detectionId(), oldest.detectionId());
        assertThat(adapter.search(
                new AbuseDetectionSearchCondition(member.getMemberId(), AbuseType.FAILURE_BURST, null),
                PageRequest.of(0, 10)).getContent())
                .extracting(AbuseDetection::detectionId)
                .containsExactly(newest.detectionId());
    }

    /** reviewIfDetected는 최초 전이만 성공시키고 같은·반대 판정의 재전이는 막는다. */
    @Test
    void DETECTED인_행만_조건부로_검토_전이한다() {
        Member detectedMember = saveMember("탐지 대상 회원");
        Member admin = saveMember("탐지 검토 관리자");
        AbuseDetection saved = adapter.save(detection(
                detectedMember.getMemberId(), AbuseType.ENTRY_REQUEST_BURST,
                Instant.parse("2026-10-01T00:00:00Z")));
        Instant reviewedAt = Instant.parse("2026-10-01T00:02:00Z");

        int firstTransition = adapter.reviewIfDetected(
                saved.detectionId(), AbuseReviewDecision.CONFIRMED, admin.getMemberId(), reviewedAt);
        int sameDecisionRetry = adapter.reviewIfDetected(
                saved.detectionId(), AbuseReviewDecision.CONFIRMED, admin.getMemberId(), reviewedAt.plusSeconds(1));
        int oppositeDecisionRetry = adapter.reviewIfDetected(
                saved.detectionId(), AbuseReviewDecision.FALSE_POSITIVE, admin.getMemberId(), reviewedAt.plusSeconds(1));

        assertThat(firstTransition).isOne();
        assertThat(sameDecisionRetry).isZero();
        assertThat(oppositeDecisionRetry).isZero();
        assertThat(adapter.findById(saved.detectionId()))
                .hasValueSatisfying(found -> {
                    assertThat(found.status()).isEqualTo(AbuseDetectionStatus.CONFIRMED);
                    assertThat(found.reviewedBy()).isEqualTo(admin.getMemberId());
                    assertThat(found.reviewedAt()).isEqualTo(reviewedAt);
                });
    }

    /** 유효하지 않은 검토 정보는 조건부 UPDATE 전에 거부해 Domain 불변식을 깨지 않는다. */
    @Test
    void 유효하지_않은_검토_정보는_DB에_반영하지_않는다() {
        Member member = saveMember("검토 검증 회원");
        AbuseDetection saved = adapter.save(detection(
                member.getMemberId(), AbuseType.FAILURE_BURST,
                Instant.parse("2026-10-01T00:00:00Z")));

        assertThatThrownBy(() -> adapter.reviewIfDetected(
                null, AbuseReviewDecision.CONFIRMED, member.getMemberId(), Instant.now()))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> adapter.reviewIfDetected(
                saved.detectionId(), null, member.getMemberId(), Instant.now()))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> adapter.reviewIfDetected(
                saved.detectionId(), AbuseReviewDecision.CONFIRMED, 0L, Instant.now()))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> adapter.reviewIfDetected(
                saved.detectionId(), AbuseReviewDecision.CONFIRMED, member.getMemberId(), null))
                .isInstanceOf(RuntimeException.class);

        assertThat(adapter.findById(saved.detectionId()))
                .hasValueSatisfying(found -> {
                    assertThat(found.status()).isEqualTo(AbuseDetectionStatus.DETECTED);
                    assertThat(found.reviewedBy()).isNull();
                    assertThat(found.reviewedAt()).isNull();
                });
    }

    /** FK를 만족하는 테스트 회원을 flush해 생성한다. */
    private Member saveMember(String name) {
        return memberRepository.saveAndFlush(new Member(name, null, null, MemberRole.USER));
    }

    /** 지정한 유형과 시각으로 저장 가능한 Detection Aggregate를 생성한다. */
    private AbuseDetection detection(Long memberId, AbuseType abuseType, Instant detectedAt) {
        DetectionEvidence evidence = new DetectionEvidence(
                DetectionEvidence.POLICY_VERSION,
                new DetectionEvidence.Scope(
                        DetectionEvidence.Scope.Type.BUSINESS_KEY,
                        10L, null, 3L, "2026-10-01", kr.co.cking.abuse.domain.BalanceScope.creator(10L)),
                new DetectionEvidence.Window(10_000L, null),
                Map.of(AbuseMetric.DUPLICATE_MISSION_FAILURE_COUNT, 8L),
                Map.of(AbuseMetric.DUPLICATE_MISSION_FAILURE_COUNT, 5L),
                Set.of(AbuseSignal.REQUEST_ID_ROTATION),
                Set.of(AbuseCompositeRule.RULE_01));
        return AbuseDetection.detected(new DetectionResult(
                memberId,
                abuseType,
                AbuseScopeHash.fromCanonicalValue("USER:" + memberId),
                detectedAt,
                evidence));
    }
}
