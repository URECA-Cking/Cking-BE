package kr.co.cking.abuse.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Instant;
import java.util.Optional;
import kr.co.cking.abuse.application.port.AbuseDetectionRepository;
import kr.co.cking.abuse.domain.AbuseDetection;
import kr.co.cking.abuse.domain.AbuseDetectionStatus;
import kr.co.cking.abuse.domain.AbuseTestFixtures;
import kr.co.cking.abuse.domain.AbuseType;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 관리자 Detection 상세 조회의 권한 검증과 미존재 오류를 확인한다. */
@ExtendWith(MockitoExtension.class)
class AdminAbuseDetectionQueryServiceTest {

    @Mock
    private MemberQueryService memberQueryService;

    @Mock
    private AbuseDetectionRepository abuseDetectionRepository;

    @InjectMocks
    private AdminAbuseDetectionQueryService queryService;

    /** 관리자는 Detection 상세를 전체 Evidence Aggregate 그대로 조회한다. */
    @Test
    void 관리자는_Detection_상세를_조회한다() {
        AbuseDetection detection = detection();
        given(abuseDetectionRepository.findById(21L)).willReturn(Optional.of(detection));

        assertThat(queryService.get(1L, 21L)).isSameAs(detection);
        then(memberQueryService).should().validateAdmin(1L);
    }

    /** 관리자 권한이 없으면 저장소 조회 전에 공통 권한 오류를 전파한다. */
    @Test
    void 관리자_권한이_없으면_FORBIDDEN이다() {
        org.mockito.Mockito.doThrow(new BusinessException(CommonErrorCode.FORBIDDEN))
                .when(memberQueryService).validateAdmin(1L);

        assertThatThrownBy(() -> queryService.get(1L, 21L))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(CommonErrorCode.FORBIDDEN);
        then(abuseDetectionRepository).shouldHaveNoInteractions();
    }

    /** 존재하지 않는 Detection은 상세 조회에서 공통 리소스 없음 오류로 변환한다. */
    @Test
    void 존재하지_않는_Detection은_RESOURCE_NOT_FOUND다() {
        given(abuseDetectionRepository.findById(21L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> queryService.get(1L, 21L))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
        then(memberQueryService).should().validateAdmin(1L);
    }

    /** 상세 조회 응답에 쓸 유효한 Detection fixture를 생성한다. */
    private AbuseDetection detection() {
        return AbuseDetection.restore(
                21L, 7L, AbuseType.FAILURE_BURST, AbuseDetectionStatus.DETECTED,
                Instant.parse("2026-10-02T00:30:00Z"), null, null, AbuseTestFixtures.userEvidence());
    }
}
