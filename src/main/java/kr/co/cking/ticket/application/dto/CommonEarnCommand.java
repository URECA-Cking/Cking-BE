package kr.co.cking.ticket.application.dto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
    /** requestId를 제외한 공용 EARN 요청 내용을 해시해 전역 requestId claim에 사용한다. */
    public String computeFingerprint() {
        String payload = userId + ":" + missionType + ":" + missionId + ":" + amount;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

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
