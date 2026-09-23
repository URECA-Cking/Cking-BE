package kr.co.cking.ticket.application.dto;

import java.util.Map;
import java.util.UUID;

import kr.co.cking.ticket.application.CommonTicketEarnService;

/**
 * 공용 미션(크리에이터 무관) 완료 시 {@link CommonTicketEarnService#earn}을 호출할 때
 * 전달하는 커맨드(이슈 #219). {@link EarnCommand}와 같은 계약이지만 {@code creatorId}가
 * 없다 — 공용은 크리에이터가 아니라서 Guard·Balance 키에도 creatorId 축이 없다.
 * {@code missionKey}도 없다 — 공용 미션은 유형당 하나뿐(uk_common_mission_type)이라
 * {@code missionType} 하나로 Guard 키가 이미 유일하다.
 */
public record CommonEarnCommand(
        UUID requestId,
        Long userId,
        String missionType,
        Long missionId,
        /** 서버 UTC 기준 {@code YYYY-MM-DD}(DB {@code common_mission_completion.period_key} 컬럼). */
        String periodKey,
        Long amount
) {
    public static CommonEarnCommand fromStreamFields(Map<String, String> fields) {
        return new CommonEarnCommand(
                UUID.fromString(fields.get("requestId")),
                Long.valueOf(fields.get("userId")),
                fields.get("missionType"),
                Long.valueOf(fields.get("missionId")),
                fields.get("periodKey"),
                Long.valueOf(fields.get("amount"))
        );
    }
}
