package kr.co.cking.drawing.repository;

import kr.co.cking.winner.domain.WinnerManagementStatus;

/** REDRAW 제외 명단 저장에 필요한 기존 Winner와 당시 운영 상태다. */
public record RedrawExclusionSource(Long memberId, WinnerManagementStatus managementStatus) {
}
