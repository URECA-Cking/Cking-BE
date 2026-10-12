package kr.co.cking.ticket.application.dto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuizRewardCommandTest {

    private static final UUID REQUEST_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Test
    void 정답_1개당_Ticket_1장을_지급한다() {
        assertThat(command(3).amount()).isEqualTo(3L);
        assertThat(command(1).amount()).isEqualTo(1L);
    }

    @Test
    void ID는_양수이고_rewardRequestId는_필수다() {
        Long[] invalid = {null, 0L, -1L};
        for (Long value : invalid) {
            assertInvalid(() -> new QuizRewardCommand(value, 7L, 10L, 3, REQUEST_ID));
            assertInvalid(() -> new QuizRewardCommand(100L, value, 10L, 3, REQUEST_ID));
            assertInvalid(() -> new QuizRewardCommand(100L, 7L, value, 3, REQUEST_ID));
        }
        assertInvalid(() -> new QuizRewardCommand(100L, 7L, 10L, 3, null));
    }

    @Test
    void 정답_수가_0_이하면_거부한다() {
        for (int correctCount : new int[] {0, -1, Integer.MIN_VALUE}) {
            assertInvalid(() -> command(correctCount));
        }
    }

    @Test
    void fingerprint는_버전과_고정_순서로_만든_SHA_256이다() throws Exception {
        String expected = "quiz-reward-fingerprint-v1|source=QUIZ|attemptId=100|memberId=7|creatorId=10|correctCount=3";

        assertThat(command(3).computeFingerprint()).isEqualTo(sha256(expected)).hasSize(64).isLowerCase();
        assertThat(QuizRewardCommand.FINGERPRINT_VERSION).isEqualTo("quiz-reward-fingerprint-v1");
        assertThat(QuizRewardCommand.SOURCE).isEqualTo("QUIZ");
    }

    @Test
    void fingerprint는_rewardRequestId와_무관하고_입력이_하나라도_바뀌면_달라진다() {
        String fingerprint = command(3).computeFingerprint();

        assertThat(new QuizRewardCommand(100L, 7L, 10L, 3, UUID.randomUUID()).computeFingerprint())
                .isEqualTo(fingerprint);
        List<QuizRewardCommand> changed = List.of(
                new QuizRewardCommand(101L, 7L, 10L, 3, REQUEST_ID),
                new QuizRewardCommand(100L, 8L, 10L, 3, REQUEST_ID),
                new QuizRewardCommand(100L, 7L, 11L, 3, REQUEST_ID),
                new QuizRewardCommand(100L, 7L, 10L, 4, REQUEST_ID),
                // 자리를 바꾼 같은 숫자도 다른 입력이다.
                new QuizRewardCommand(7L, 100L, 10L, 3, REQUEST_ID));
        changed.forEach(other -> assertThat(other.computeFingerprint()).isNotEqualTo(fingerprint));
    }

    private QuizRewardCommand command(int correctCount) {
        return new QuizRewardCommand(100L, 7L, 10L, correctCount, REQUEST_ID);
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(IllegalArgumentException.class);
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
