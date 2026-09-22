package kr.co.cking.redraw.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.redraw.domain.RedrawErrorCode;
import kr.co.cking.redraw.domain.RedrawExecutionStatus;
import kr.co.cking.redraw.domain.RedrawRequest;
import kr.co.cking.redraw.domain.RedrawRequestStatus;
import kr.co.cking.redraw.repository.RedrawRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 관리자 RedrawRequest 심사의 권한·상태 전이·잠금 조회를 검증한다. */
@ExtendWith(MockitoExtension.class)
class RedrawRequestReviewServiceTest {

    private static final Long ADMIN_ID = 1L;
    private static final Long REDRAW_REQUEST_ID = 10L;

    @Mock
    private MemberQueryService memberQueryService;

    @Mock
    private RedrawRequestRepository redrawRequestRepository;

    private RedrawRequestReviewService service;

    /** 심사 Service에 필요한 협력 객체를 매 테스트 전에 조립한다. */
    @BeforeEach
    void setUp() {
        service = new RedrawRequestReviewService(memberQueryService, redrawRequestRepository);
    }

    /** 관리자는 잠금으로 조회한 REQUESTED 요청을 승인하고 실행 대기 상태를 유지한다. */
    @Test
    void 관리자는_검토_대기_요청을_승인한다() {
        RedrawRequest request = requested();
        when(redrawRequestRepository.findByIdForUpdate(REDRAW_REQUEST_ID)).thenReturn(Optional.of(request));

        RedrawRequestReviewResult result = service.approve(ADMIN_ID, REDRAW_REQUEST_ID);

        verify(memberQueryService).validateAdmin(ADMIN_ID);
        verify(redrawRequestRepository).findByIdForUpdate(REDRAW_REQUEST_ID);
        assertThat(result.status()).isEqualTo(RedrawRequestStatus.APPROVED);
        assertThat(result.executionStatus()).isEqualTo(RedrawExecutionStatus.PENDING);
        assertThat(result.reviewedBy()).isEqualTo(ADMIN_ID);
        assertThat(result.reviewedAt()).isNotNull();
        assertThat(result.rejectReason()).isNull();
    }

    /** 관리자는 잠금으로 조회한 REQUESTED 요청을 거절하고 심사 사유를 기록한다. */
    @Test
    void 관리자는_검토_대기_요청을_거절한다() {
        RedrawRequest request = requested();
        when(redrawRequestRepository.findByIdForUpdate(REDRAW_REQUEST_ID)).thenReturn(Optional.of(request));

        RedrawRequestReviewResult result = service.reject(ADMIN_ID, REDRAW_REQUEST_ID, " 결원 확인이 필요합니다. ");

        assertThat(result.status()).isEqualTo(RedrawRequestStatus.REJECTED);
        assertThat(result.executionStatus()).isEqualTo(RedrawExecutionStatus.PENDING);
        assertThat(result.reviewedBy()).isEqualTo(ADMIN_ID);
        assertThat(result.reviewedAt()).isNotNull();
        assertThat(result.rejectReason()).isEqualTo("결원 확인이 필요합니다.");
    }

    /** 요청이 검토 대기 상태가 아니면 심사를 다시 수행할 수 없다. */
    @Test
    void 이미_심사된_요청은_INVALID_STATE다() {
        RedrawRequest request = requested();
        request.approve(ADMIN_ID);
        when(redrawRequestRepository.findByIdForUpdate(REDRAW_REQUEST_ID)).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.reject(ADMIN_ID, REDRAW_REQUEST_ID, "사유"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(RedrawErrorCode.INVALID_STATE);
    }

    /** 거절 사유가 비어 있으면 상태를 바꾸지 않고 입력 오류로 거부한다. */
    @Test
    void 빈_거절_사유는_VALIDATION_FAILED다() {
        RedrawRequest request = requested();
        when(redrawRequestRepository.findByIdForUpdate(REDRAW_REQUEST_ID)).thenReturn(Optional.of(request));

        assertThatThrownBy(() -> service.reject(ADMIN_ID, REDRAW_REQUEST_ID, " "))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
        assertThat(request.getStatus()).isEqualTo(RedrawRequestStatus.REQUESTED);
    }

    /** 존재하지 않는 요청은 Redraw 도메인의 요청 없음 오류로 반환한다. */
    @Test
    void 없는_요청은_REDRAW_REQUEST_NOT_FOUND다() {
        when(redrawRequestRepository.findByIdForUpdate(REDRAW_REQUEST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(ADMIN_ID, REDRAW_REQUEST_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(RedrawErrorCode.REDRAW_REQUEST_NOT_FOUND);
    }

    /** 관리자 검증이 실패하면 잠금 조회와 상태 전이를 수행하지 않는다. */
    @Test
    void 관리자_검증_실패_시_요청을_조회하지_않는다() {
        doThrow(new BusinessException(CommonErrorCode.FORBIDDEN))
                .when(memberQueryService).validateAdmin(ADMIN_ID);

        assertThatThrownBy(() -> service.approve(ADMIN_ID, REDRAW_REQUEST_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.FORBIDDEN);
        verifyNoInteractions(redrawRequestRepository);
    }

    /** 심사 가능한 검토 대기 RedrawRequest를 만든다. */
    private RedrawRequest requested() {
        return RedrawRequest.requested(20L, 30L, 1, "당첨자 포기", "key-" + REDRAW_REQUEST_ID, ADMIN_ID);
    }
}
