package kr.co.cking.ticket.application.dto;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * {@code stream:ticket-earned}의 Quiz 보상 EARN 메시지 v1(이슈 #533). 기존 Mission 메시지와 같은 Stream을 쓰고
 * {@code sourceType=QUIZ}, {@code payloadVersion=1}로 구분한다.
 *
 * <p>{@code userId}는 기존 Stream 소비자({@code UnappliedBalanceMessageChecker} 등)가 읽는 필드라 {@code memberId}와
 * 같은 값으로 함께 싣는다. 지급량은 메시지 값을 그대로 믿지 않고 DB 반영 때 저장된 요청으로 다시 정한다.
 * {@code executionToken}은 Redis 실행권 식별자이며 의미 검증은 실행권 정책에서 한다.
 */
public record QuizEarnEvent(
        UUID requestId,
        Long attemptId,
        Long memberId,
        Long creatorId,
        int correctCount,
        long amount,
        String fingerprintVersion,
        String fingerprint,
        String executionToken
) {

    public static final String SOURCE_TYPE = QuizRewardCommand.SOURCE;
    public static final String PAYLOAD_VERSION = "1";

    /** 모든 필드와 상호 관계를 검사한다. 어기면 DB에 반영하지 않도록 {@link IllegalArgumentException}이다. */
    public QuizEarnEvent {
        if (requestId == null) {
            throw new IllegalArgumentException("Quiz EARN 메시지에 requestId가 없습니다.");
        }
        if (amount != correctCount) {
            throw new IllegalArgumentException("Quiz EARN 지급량이 정답 수와 다릅니다. requestId=" + requestId);
        }
        if (!QuizRewardCommand.FINGERPRINT_VERSION.equals(fingerprintVersion)) {
            throw new IllegalArgumentException("지원하지 않는 Quiz EARN fingerprint 버전입니다. requestId=" + requestId);
        }
        if (executionToken == null || executionToken.isBlank()) {
            throw new IllegalArgumentException("Quiz EARN 메시지에 executionToken이 없습니다. requestId=" + requestId);
        }
        // ID·정답 수 검증과 fingerprint 계산은 접수 계약과 같은 규칙을 쓴다.
        String expected = toCommand(requestId, attemptId, memberId, creatorId, correctCount).computeFingerprint();
        if (!expected.equals(fingerprint)) {
            throw new IllegalArgumentException("Quiz EARN 메시지의 fingerprint가 내용과 다릅니다. requestId=" + requestId);
        }
    }

    /**
     * Stream 필드를 v1 메시지로 바꾼다. source·version이 다르거나, 필드가 없거나, 숫자·UUID 형식이 틀리거나,
     * {@code userId != memberId}면 {@link IllegalArgumentException}이다.
     */
    public static QuizEarnEvent fromStreamFields(Map<String, String> fields) {
        if (!SOURCE_TYPE.equals(fields.get("sourceType")) || !PAYLOAD_VERSION.equals(fields.get("payloadVersion"))) {
            throw new IllegalArgumentException("Quiz EARN v1 메시지가 아닙니다. sourceType=" + fields.get("sourceType")
                    + ", payloadVersion=" + fields.get("payloadVersion"));
        }
        Long memberId = longField(fields, "memberId");
        if (!memberId.equals(longField(fields, "userId"))) {
            throw new IllegalArgumentException("Quiz EARN 메시지의 userId와 memberId가 다릅니다.");
        }
        UUID requestId;
        try {
            requestId = UUID.fromString(required(fields, "requestId"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Quiz EARN 메시지의 requestId가 UUID가 아닙니다.", e);
        }
        if (!requestId.toString().equals(fields.get("requestId"))) {
            throw new IllegalArgumentException("Quiz EARN 메시지의 requestId가 정규 UUID 형식이 아닙니다.");
        }
        long correctCount = longField(fields, "correctCount");
        // int로 바꾸기 전에 범위를 확인한다. 큰 음수가 변환 뒤 양수로 바뀌어 통과하지 않게 한다.
        if (correctCount < 1 || correctCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Quiz EARN 메시지의 correctCount가 1 이상 int 범위가 아닙니다.");
        }
        return new QuizEarnEvent(requestId, longField(fields, "attemptId"), memberId, longField(fields, "creatorId"),
                (int) correctCount, longField(fields, "amount"), required(fields, "fingerprintVersion"),
                required(fields, "fingerprint"), required(fields, "executionToken"));
    }

    /** Stream에 싣는 필드. 소비 쪽 {@link #fromStreamFields}와 짝이다. */
    public Map<String, String> toStreamFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("sourceType", SOURCE_TYPE);
        fields.put("payloadVersion", PAYLOAD_VERSION);
        fields.put("requestId", requestId.toString());
        fields.put("attemptId", String.valueOf(attemptId));
        fields.put("memberId", String.valueOf(memberId));
        fields.put("userId", String.valueOf(memberId));
        fields.put("creatorId", String.valueOf(creatorId));
        fields.put("correctCount", String.valueOf(correctCount));
        fields.put("amount", String.valueOf(amount));
        fields.put("fingerprintVersion", fingerprintVersion);
        fields.put("fingerprint", fingerprint);
        fields.put("executionToken", executionToken);
        return fields;
    }

    private static QuizRewardCommand toCommand(UUID requestId, Long attemptId, Long memberId, Long creatorId,
                                               int correctCount) {
        return new QuizRewardCommand(attemptId, memberId, creatorId, correctCount, requestId);
    }

    private static String required(Map<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Quiz EARN 메시지에 " + name + "가 없습니다.");
        }
        return value;
    }

    private static Long longField(Map<String, String> fields, String name) {
        String value = required(fields, name);
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Quiz EARN 메시지의 " + name + "가 숫자가 아닙니다.", e);
        }
    }
}
