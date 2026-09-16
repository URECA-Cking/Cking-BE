package kr.co.cking.ticket.application.dto;

import java.util.UUID;

import kr.co.cking.ticket.application.TicketEarnService;

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
        /**
         * KST 기준 {@code YYYY-MM-DD} (DB {@code mission_completion.period_key} 컬럼,
         * API 명세 §3.2와 동일한 형식). RTM의 미션 Redis 가드 키에 박히는
         * {@code yyyyMMdd} 세그먼트와는 별개 값이니 혼동하지 않는다.
         */
        String periodKey,
        String missionKey,
        Long amount
) {
}
