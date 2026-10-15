package kr.co.cking.ticket.application.dto;

import java.time.Instant;
import java.util.List;

/**
 * 내 응모권 요약. 공용 잔액과 팔로우한 Creator별 잔액, 그리고 서버가 전체 기준으로 계산한 합계를 한 번에 돌려준다.
 * 잔액은 DB 기준의 표시용 값이며 응모 가능 여부의 근거가 아니다.
 *
 * @param creatorTotal 팔로우한 모든 Creator 잔액의 합. {@code creators}를 나중에 제한해도 바뀌지 않도록 서버가 계산한다.
 * @param total        공용 잔액과 {@code creatorTotal}의 합
 */
public record MyTicketsSummaryResponse(CommonBalance common, List<CreatorTicket> creators, long creatorTotal, long total) {

    public record CommonBalance(long balance, Instant updatedAt) {
    }

    /** 잔액 행이 없으면 {@code balance}는 0, {@code updatedAt}은 null이다. Space가 없으면 {@code profileImageUrl}은 null이다. */
    public record CreatorTicket(Long creatorId, String creatorName, String profileImageUrl, long balance,
                                Instant updatedAt) {
    }
}
