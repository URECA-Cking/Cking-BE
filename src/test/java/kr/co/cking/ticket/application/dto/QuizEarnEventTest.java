package kr.co.cking.ticket.application.dto;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuizEarnEventTest {

    private static final UUID REQUEST_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Test
    void v1_필드를_읽어_메시지를_만들고_같은_필드로_되돌린다() {
        Map<String, String> fields = fields();

        QuizEarnEvent event = QuizEarnEvent.fromStreamFields(fields);

        assertThat(event.requestId()).isEqualTo(REQUEST_ID);
        assertThat(event.attemptId()).isEqualTo(100L);
        assertThat(event.memberId()).isEqualTo(7L);
        assertThat(event.creatorId()).isEqualTo(10L);
        assertThat(event.correctCount()).isEqualTo(3);
        assertThat(event.amount()).isEqualTo(3L);
        assertThat(event.executionToken()).isEqualTo("token-1");
        assertThat(event.toStreamFields()).isEqualTo(fields);
    }

    @Test
    void source나_버전이_다르면_거부한다() {
        assertInvalid(fields -> fields.remove("sourceType"));
        assertInvalid(fields -> fields.put("sourceType", "MISSION"));
        assertInvalid(fields -> fields.put("sourceType", "quiz"));
        assertInvalid(fields -> fields.remove("payloadVersion"));
        assertInvalid(fields -> fields.put("payloadVersion", "2"));
    }

    @Test
    void 필드가_없거나_형식이_틀리면_거부한다() {
        for (String name : new String[] {"requestId", "attemptId", "memberId", "userId", "creatorId", "correctCount",
                "amount", "fingerprintVersion", "fingerprint", "executionToken"}) {
            assertInvalid(fields -> fields.remove(name));
            assertInvalid(fields -> fields.put(name, " "));
        }
        assertInvalid(fields -> fields.put("attemptId", "abc"));
        assertInvalid(fields -> fields.put("amount", "3.0"));
        assertInvalid(fields -> fields.put("requestId", "not-a-uuid"));
        assertInvalid(fields -> fields.put("requestId", REQUEST_ID.toString().toUpperCase()));
        assertInvalid(fields -> fields.put("correctCount", String.valueOf(Integer.MAX_VALUE + 1L)));
    }

    @Test
    void correctCount는_int로_바꾸기_전에_1_이상_int_범위인지_확인한다() {
        // -4294967295를 int로 바꾸면 1이 된다. 정답 수 1로 계산한 fingerprint·지급량이어도 거부해야 한다.
        assertThat((int) -4294967295L).isEqualTo(1);
        assertInvalid(fields -> {
            fields.put("correctCount", "-4294967295");
            fields.put("amount", "1");
            fields.put("fingerprint", new QuizRewardCommand(100L, 7L, 10L, 1, REQUEST_ID).computeFingerprint());
        });
        assertInvalid(fields -> {
            fields.put("correctCount", "0");
            fields.put("amount", "0");
        });
        assertInvalid(fields -> fields.put("correctCount", String.valueOf(Integer.MAX_VALUE + 1L)));
        assertInvalid(fields -> fields.put("correctCount", String.valueOf(Long.MIN_VALUE)));
    }

    @Test
    void userId와_memberId가_다르거나_지급량이_정답_수와_다르면_거부한다() {
        assertInvalid(fields -> fields.put("userId", "8"));
        assertInvalid(fields -> fields.put("amount", "4"));
    }

    @Test
    void ID와_정답_수가_양수가_아니면_거부한다() {
        assertInvalid(fields -> {
            fields.put("attemptId", "0");
        });
        assertInvalid(fields -> {
            fields.put("memberId", "-1");
            fields.put("userId", "-1");
        });
        assertInvalid(fields -> fields.put("creatorId", "0"));
        assertInvalid(fields -> {
            fields.put("correctCount", "0");
            fields.put("amount", "0");
        });
    }

    @Test
    void fingerprint가_내용과_다르거나_버전이_다르면_거부한다() {
        assertInvalid(fields -> fields.put("fingerprint", "f".repeat(64)));
        // 정답 수를 바꾸면 fingerprint가 맞지 않는다.
        assertInvalid(fields -> {
            fields.put("correctCount", "4");
            fields.put("amount", "4");
        });
        assertInvalid(fields -> fields.put("fingerprintVersion", "quiz-reward-fingerprint-v2"));
    }

    private static Map<String, String> fields() {
        QuizRewardCommand command = new QuizRewardCommand(100L, 7L, 10L, 3, REQUEST_ID);
        Map<String, String> fields = new HashMap<>();
        fields.put("sourceType", "QUIZ");
        fields.put("payloadVersion", "1");
        fields.put("requestId", REQUEST_ID.toString());
        fields.put("attemptId", "100");
        fields.put("memberId", "7");
        fields.put("userId", "7");
        fields.put("creatorId", "10");
        fields.put("correctCount", "3");
        fields.put("amount", "3");
        fields.put("fingerprintVersion", QuizRewardCommand.FINGERPRINT_VERSION);
        fields.put("fingerprint", command.computeFingerprint());
        fields.put("executionToken", "token-1");
        return fields;
    }

    private static void assertInvalid(Consumer<Map<String, String>> change) {
        Map<String, String> fields = fields();
        change.accept(fields);
        assertThatThrownBy(() -> QuizEarnEvent.fromStreamFields(fields)).isInstanceOf(IllegalArgumentException.class);
    }
}
