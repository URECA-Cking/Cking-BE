package kr.co.cking.abuse.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import kr.co.cking.abuse.application.port.AbuseDetectionRepository;
import kr.co.cking.abuse.domain.AbuseDetection;
import kr.co.cking.abuse.domain.AbuseDetectionErrorCode;
import kr.co.cking.abuse.domain.AbuseDetectionStatus;
import kr.co.cking.abuse.domain.AbuseReviewDecision;
import kr.co.cking.abuse.domain.AbuseTestFixtures;
import kr.co.cking.abuse.domain.AbuseType;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.member.application.MemberQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 관리자 Detection 검토의 조건부 전이 후 분기 규칙을 검증한다. */
@ExtendWith(MockitoExtension.class)
class AdminAbuseDetectionReviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-03T00:00:00Z");

    @Mock
    private MemberQueryService memberQueryService;

    @Mock
    private AbuseDetectionRepository abuseDetectionRepository;

    /** 같은 판정 재요청은 기존 검토 정보 그대로 멱등 반환한다. */
    @Test
    void 같은_판정_재요청은_기존_Detection을_멱등_반환한다() {
        AdminAbuseDetectionReviewService service = service();
        AbuseDetection confirmed = reviewed(AbuseDetectionStatus.CONFIRMED);
        given(abuseDetectionRepository.reviewIfDetected(
                21L, AbuseReviewDecision.CONFIRMED, 1L, NOW)).willReturn(0);
        given(abuseDetectionRepository.findByIdForShare(21L)).willReturn(Optional.of(confirmed));

        AbuseDetection result = service.review(1L, 21L, AbuseReviewDecision.CONFIRMED);

        assertThat(result).isSameAs(confirmed);
        then(memberQueryService).should().validateAdmin(1L);
    }

    /** 다른 종결 판정이 이미 반영됐으면 검토자를 덮어쓰지 않고 상태 충돌을 반환한다. */
    @Test
    void 상반된_판정_재요청은_상태_충돌이다() {
        AdminAbuseDetectionReviewService service = service();
        given(abuseDetectionRepository.reviewIfDetected(
                21L, AbuseReviewDecision.FALSE_POSITIVE, 1L, NOW)).willReturn(0);
        given(abuseDetectionRepository.findByIdForShare(21L))
                .willReturn(Optional.of(reviewed(AbuseDetectionStatus.CONFIRMED)));

        assertThatThrownBy(() -> service.review(1L, 21L, AbuseReviewDecision.FALSE_POSITIVE))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(AbuseDetectionErrorCode.INVALID_STATE);
    }

    /** 조건부 전이가 성공하면 UTC Clock 시각으로 갱신된 Detection을 반환한다. */
    @Test
    void 최초_검토는_조건부_전이_뒤_갱신된_Detection을_반환한다() {
        AdminAbuseDetectionReviewService service = service();
        AbuseDetection confirmed = reviewed(AbuseDetectionStatus.CONFIRMED);
        given(abuseDetectionRepository.reviewIfDetected(
                21L, AbuseReviewDecision.CONFIRMED, 1L, NOW)).willReturn(1);
        given(abuseDetectionRepository.findById(21L)).willReturn(Optional.of(confirmed));

        assertThat(service.review(1L, 21L, AbuseReviewDecision.CONFIRMED)).isSameAs(confirmed);
        then(abuseDetectionRepository).should().reviewIfDetected(
                21L, AbuseReviewDecision.CONFIRMED, 1L, NOW);
    }

    /** 조건부 UPDATE 뒤 행이 없으면 존재하지 않는 Detection 오류로 변환한다. */
    @Test
    void 존재하지_않는_Detection은_404다() {
        AdminAbuseDetectionReviewService service = service();
        given(abuseDetectionRepository.reviewIfDetected(any(), any(), any(), any())).willReturn(0);
        given(abuseDetectionRepository.findByIdForShare(21L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.review(1L, 21L, AbuseReviewDecision.CONFIRMED))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(kr.co.cking.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    /** 고정 UTC Clock을 주입한 검토 서비스를 생성한다. */
    private AdminAbuseDetectionReviewService service() {
        return new AdminAbuseDetectionReviewService(
                memberQueryService,
                abuseDetectionRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** 검토 완료 상태와 보존된 검토 정보가 있는 Detection fixture를 생성한다. */
    private AbuseDetection reviewed(AbuseDetectionStatus status) {
        return AbuseDetection.restore(
                21L, 7L, AbuseType.FAILURE_BURST, status,
                Instant.parse("2026-10-02T00:00:00Z"), NOW, 1L, AbuseTestFixtures.userEvidence());
    }
}
