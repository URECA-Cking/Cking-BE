package kr.co.cking.ticket.application.dto;

import java.util.Map;
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
         * 서버 UTC 기준 {@code YYYY-MM-DD} (DB {@code mission_completion.period_key} 컬럼,
         * API 명세 §3.2와 동일한 형식, RTM FR-P1-006/FR-P1-021/FR-P2-006). RTM의 미션
         * Redis 가드 키에 박히는 {@code yyyyMMdd} 세그먼트와는 별개 값이니 혼동하지 않는다.
         */
        String periodKey,
        String missionKey,
        Long amount
) {
    /**
     * {@code stream:ticket-earned} Redis Stream 메시지 필드(정상 소비·PEL 재처리·Dead
     * Stream replay 공통 포맷)를 커맨드로 변환한다.
     */
    public static EarnCommand fromStreamFields(Map<String, String> fields) {
        return new EarnCommand(
                UUID.fromString(fields.get("requestId")),
                Long.valueOf(fields.get("userId")),
                Long.valueOf(fields.get("creatorId")),
                fields.get("missionType"),
                Long.valueOf(fields.get("missionId")),
                fields.get("periodKey"),
                fields.get("missionKey"),
                Long.valueOf(fields.get("amount"))
        );
    }
}
