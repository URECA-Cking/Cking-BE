package kr.co.cking.winner.application;

import java.time.Instant;
import kr.co.cking.winner.domain.WinnerManagementStatus;
import kr.co.cking.winner.domain.WinnerStatusHistory;

/** Winner 상태 변경 전후와 감사 정보를 외부에 반환하는 읽기 전용 결과다. */
public record WinnerStatusHistoryResult(
        Long historyId,
        WinnerManagementStatus beforeStatus,
        WinnerManagementStatus afterStatus,
        String reason,
        Long changedBy,
        Instant changedAt
) {

    /** 영속된 상태 이력을 외부 API 응답 형식으로 변환한다. */
    public static WinnerStatusHistoryResult from(WinnerStatusHistory history) {
        return new WinnerStatusHistoryResult(
                history.getId(),
                history.getPreviousStatus(),
                history.getStatus(),
                history.getReason(),
                history.getChangedBy(),
                history.getCreatedAt()
        );
    }
}
