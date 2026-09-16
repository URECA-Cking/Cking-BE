package kr.co.cking.ticket;

import java.util.UUID;

/**
 * 미션 완료(T1) 쪽에서 {@link TicketEarnService#earn}을 호출할 때 전달하는 커맨드.
 * 필드 구성은 통합 API 명세 v2.5 §3.5 기준.
 */
public record EarnCommand(
        UUID requestId,
        Long userId,
        Long creatorId,
        String missionType,
        Long missionId,
        String periodKey,
        String missionKey,
        Long amount
) {
}
