package kr.co.cking.ticket.application.dto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Quiz 응시(attempt) 보상 Ticket 지급 요청(이슈 #533). 서버 내부 계약이며 공개 API로 받지 않는다.
 *
 * <p>호출자(Quiz 도메인)는 서버에서 채점한 attempt의 소유자·Creator·정답 수를 넘기고, 보상량은 정답 1개당 1장으로
 * Ticket 쪽에서 정한다. {@code rewardRequestId}는 호출자가 attempt마다 한 번 만들어 고정한 UUID이며 재시도·복구에서도
 * 바꾸지 않는다. 정답이 0개면 호출자가 지급을 요청하지 않으며, Ticket 경계에서도 0 이하를 거부한다. attempt가 실제로
 * 그 회원의 것인지와 채점 결과는 호출자가 보장하고 Ticket은 DB로 검증하지 않는다.
 *
 * @param correctCount 정답 수. 1 이상
 */
public record QuizRewardCommand(
        Long attemptId,
        Long memberId,
        Long creatorId,
        int correctCount,
        UUID rewardRequestId
) {

    /** 입력 fingerprint 규칙 버전. 규칙을 바꾸면 올리고 기존 요청은 기존 규칙으로 비교한다. */
    public static final String FINGERPRINT_VERSION = "quiz-reward-fingerprint-v1";
    /** fingerprint와 전역 EARN request의 적립 원인. */
    public static final String SOURCE = "QUIZ";

    public QuizRewardCommand {
        requirePositive(attemptId, "attemptId");
        requirePositive(memberId, "memberId");
        requirePositive(creatorId, "creatorId");
        if (correctCount <= 0) {
            throw new IllegalArgumentException("Quiz 보상 정답 수는 1 이상이어야 합니다: " + correctCount);
        }
        if (rewardRequestId == null) {
            throw new IllegalArgumentException("Quiz 보상 rewardRequestId가 필요합니다.");
        }
    }

    /** 지급할 Ticket 수. 정답 1개당 1장이다. */
    public long amount() {
        return correctCount;
    }

    /**
     * rewardRequestId와 시각을 뺀 요청 내용의 SHA-256(소문자 hex). 아래 값을 이 순서로 {@code |}로 이은 UTF-8 바이트다.
     * 값은 모두 버전 문자열·고정 문자열·10진수 정수라 구분자와 섞이지 않는다.
     * <pre>{@value #FINGERPRINT_VERSION}|source=QUIZ|attemptId=..|memberId=..|creatorId=..|correctCount=..</pre>
     */
    public String computeFingerprint() {
        String payload = FINGERPRINT_VERSION
                + "|source=" + SOURCE
                + "|attemptId=" + attemptId
                + "|memberId=" + memberId
                + "|creatorId=" + creatorId
                + "|correctCount=" + correctCount;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    private static void requirePositive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException("Quiz 보상 " + field + "는 양수여야 합니다: " + value);
        }
    }
}
