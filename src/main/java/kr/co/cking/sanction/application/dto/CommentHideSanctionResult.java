package kr.co.cking.sanction.application.dto;

import java.time.Instant;

/**
 * 관리자의 댓글 숨김으로 쌓인 경고와 그 결과 제한.
 *
 * @param warningCount 집계 기간 안의 누적 경고 횟수(이번 경고 포함)
 * @param newlyRecorded 이번 호출로 경고가 새로 쌓였으면 true. 같은 댓글로 이미 쌓인 경고면 false
 */
public record CommentHideSanctionResult(
        Long sanctionId, long warningCount, Instant startedAt, Instant endsAt, boolean newlyRecorded) {
}
