package kr.co.cking.ticket.application.dto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** 공용 응모권을 회원·보상 종류별 평생 한 번 적립하는 Ticket 내부 요청이다. */
public record CommonOnceEarnCommand(UUID requestId, Long userId, String rewardType, Long amount,
                                    String requestContext) {

    /** 전역 requestId와 영속 지급 원인에서 동일 요청 내용을 비교할 해시를 만든다. */
    public String computeFingerprint() {
        String payload = userId + ":" + rewardType + ":" + amount + ":COMMON_ONCE:" + requestContext;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }
}
