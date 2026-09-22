package kr.co.cking.drawing.domain;

import kr.co.cking.winner.domain.WinnerManagementStatus;

/** REDRAW 입력에서 기존 당첨자를 제외한 당시의 사유다. */
public enum RedrawExclusionReason {
    ALREADY_WINNER,
    DECLINED,
    DISQUALIFIED;

    /** Winner의 당시 운영 상태를 감사용 제외 사유로 정규화한다. */
    public static RedrawExclusionReason from(WinnerManagementStatus status) {
        if (status == null) {
            return ALREADY_WINNER;
        }
        return switch (status) {
            case DECLINED -> DECLINED;
            case DISQUALIFIED -> DISQUALIFIED;
            case SELECTED, RECEIVED -> ALREADY_WINNER;
        };
    }
}
