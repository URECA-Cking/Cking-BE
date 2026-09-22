package kr.co.cking.redraw.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import org.junit.jupiter.api.Test;

/** RedrawRequest 심사 상태 전이와 거절 사유 불변식을 검증한다. */
class RedrawRequestTest {

    private static final Long REQUESTER_ID = 1L;
    private static final Long REVIEWER_ID = 2L;

    /** 검토 대기 요청을 승인하면 실행 대기 상태를 유지하며 심사 정보를 기록한다. */
    @Test
    void 검토_대기_요청을_승인하면_심사_정보를_기록한다() {
        RedrawRequest request = requested();

        request.approve(REVIEWER_ID);

        assertThat(request.getStatus()).isEqualTo(RedrawRequestStatus.APPROVED);
        assertThat(request.getExecutionStatus()).isEqualTo(RedrawExecutionStatus.PENDING);
        assertThat(request.getReviewedBy()).isEqualTo(REVIEWER_ID);
        assertThat(request.getReviewedAt()).isNotNull();
        assertThat(request.getRejectReason()).isNull();
    }

    /** 검토 대기 요청을 거절하면 공백 제거한 사유와 심사 정보를 기록한다. */
    @Test
    void 검토_대기_요청을_거절하면_사유와_심사_정보를_기록한다() {
        RedrawRequest request = requested();

        request.reject(REVIEWER_ID, " 결원 확인이 필요합니다. ");

        assertThat(request.getStatus()).isEqualTo(RedrawRequestStatus.REJECTED);
        assertThat(request.getExecutionStatus()).isEqualTo(RedrawExecutionStatus.PENDING);
        assertThat(request.getReviewedBy()).isEqualTo(REVIEWER_ID);
        assertThat(request.getReviewedAt()).isNotNull();
        assertThat(request.getRejectReason()).isEqualTo("결원 확인이 필요합니다.");
    }

    /** null·공백·500자 초과 거절 사유는 공통 입력 검증 오류로 거부한다. */
    @Test
    void 유효하지_않은_거절_사유는_VALIDATION_FAILED다() {
        assertThatThrownBy(() -> requested().reject(REVIEWER_ID, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> requested().reject(REVIEWER_ID, " "))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> requested().reject(REVIEWER_ID, "가".repeat(501)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    /** 이미 승인 또는 거절된 요청은 어떤 심사 명령으로도 다시 상태 전이할 수 없다. */
    @Test
    void 이미_심사된_요청은_INVALID_STATE다() {
        RedrawRequest approvedRequest = requested();
        approvedRequest.approve(REVIEWER_ID);
        assertInvalidState(() -> approvedRequest.approve(REVIEWER_ID));
        assertInvalidState(() -> approvedRequest.reject(REVIEWER_ID, "사유"));

        RedrawRequest rejectedRequest = requested();
        rejectedRequest.reject(REVIEWER_ID, "사유");
        assertInvalidState(() -> rejectedRequest.approve(REVIEWER_ID));
        assertInvalidState(() -> rejectedRequest.reject(REVIEWER_ID, "사유"));
    }

    /** null·0·음수 심사자 ID는 승인과 거절 모두에서 도메인 불변식을 위반한다. */
    @Test
    void 유효하지_않은_심사자_ID는_IllegalArgumentException이다() {
        assertThatThrownBy(() -> requested().approve(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> requested().approve(0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> requested().approve(-1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> requested().reject(null, "사유"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> requested().reject(0L, "사유"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> requested().reject(-1L, "사유"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 모든 실행 종료 상태 전이는 완료 시각을 함께 기록한다. */
    @Test
    void 실행_종료_상태_전이는_완료_시각을_기록한다() {
        RedrawRequest executed = approvedRequest();
        executed.markExecuted();
        RedrawRequest insufficientCandidates = approvedRequest();
        insufficientCandidates.markInsufficientCandidates();
        RedrawRequest failed = approvedRequest();
        failed.markFailed();

        assertThat(executed.getCompletedAt()).isNotNull();
        assertThat(insufficientCandidates.getCompletedAt()).isNotNull();
        assertThat(failed.getCompletedAt()).isNotNull();
    }

    /** 이미 심사된 요청에 대한 명령이 도메인 상태 오류를 반환하는지 검증한다. */
    private void assertInvalidState(Runnable command) {
        assertThatThrownBy(command::run)
                .isInstanceOf(BusinessException.class)
                .hasMessage("현재 상태에서는 재추첨 요청을 처리할 수 없습니다.")
                .extracting("errorCode")
                .isEqualTo(RedrawErrorCode.INVALID_STATE);
    }

    /** 심사 전 REQUESTED·PENDING 상태의 유효한 RedrawRequest를 만든다. */
    private RedrawRequest requested() {
        return RedrawRequest.requested(10L, 20L, 1, "당첨자 포기", "test-key", REQUESTER_ID);
    }

    private RedrawRequest approvedRequest() {
        RedrawRequest request = requested();
        request.approve(REVIEWER_ID);
        return request;
    }
}
