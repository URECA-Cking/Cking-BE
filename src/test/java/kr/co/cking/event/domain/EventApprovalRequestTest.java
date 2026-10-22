package kr.co.cking.event.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventApprovalRequestTest {

    /** 거절 심사가 차수별 승인 요청에 심사자·시각·사유를 기록하는지 검증한다. */
    @Test
    void rejectionStoresReviewerTimestampAndReason() {
        EventApprovalRequest request = new EventApprovalRequest(1L, 2, 10L);

        request.reject(99L, "일정이 이미 종료되었습니다.");

        assertThat(request.getStatus()).isEqualTo(EventApprovalRequestStatus.REJECTED);
        assertThat(request.getReviewedBy()).isEqualTo(99L);
        assertThat(request.getReviewedAt()).isNotNull();
        assertThat(request.getRejectReason()).isEqualTo("일정이 이미 종료되었습니다.");
    }
}
