package kr.co.cking.ticket.application.dto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
        Long amount,
        EarnRewardPolicy rewardPolicy
) {
    /** 기존 일일 미션 호출부가 DAILY 정책을 유지하도록 호환 생성자를 제공한다. */
    public EarnCommand(UUID requestId, Long userId, Long creatorId, String missionType, Long missionId,
                       String periodKey, String missionKey, Long amount) {
        this(requestId, userId, creatorId, missionType, missionId, periodKey, missionKey, amount,
                EarnRewardPolicy.DAILY);
    }

    /** 이 EARN 요청의 DB 완료 이력 중복 방어 키를 만든다. */
    public String completionKey() {
        return rewardPolicy.completionKey(periodKey);
    }

    /**
     * requestId를 제외한 EARN 요청 내용을 해시해 Redis replay와 ONCE durable request의
     * REQUEST_ID_CONFLICT 판정에 사용한다. periodKey는 서버 파생값이므로 제외한다.
     */
    public String computeFingerprint() {
        String payload = userId + ":" + creatorId + ":" + missionType + ":" + missionId + ":" + missionKey
                + ":" + amount;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

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
                Long.valueOf(fields.get("amount")),
                EarnRewardPolicy.valueOf(fields.getOrDefault("rewardPolicy", EarnRewardPolicy.DAILY.name()))
        );
    }
}
