package kr.co.cking.ticket.domain;

import kr.co.cking.ticket.application.dto.QuizRewardCommand;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketQuizRewardRequestTest {

    private static final Instant NOW = Instant.parse("2026-10-12T06:00:00Z");
    private final QuizRewardCommand command =
            new QuizRewardCommand(100L, 7L, 10L, 3, UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));

    @Test
    void RECEIVED로_접수하고_보상량은_정답_수와_같다() {
        TicketQuizRewardRequest request = request(command);

        assertThat(request.getStatus()).isEqualTo(QuizRewardRequestStatus.RECEIVED);
        assertThat(request.getRewardRequestId()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
        assertThat(request.getAttemptId()).isEqualTo(100L);
        assertThat(request.getMemberId()).isEqualTo(7L);
        assertThat(request.getCreatorId()).isEqualTo(10L);
        assertThat(request.getCorrectCount()).isEqualTo(3);
        assertThat(request.getAmount()).isEqualTo(3L);
        assertThat(request.getFingerprintVersion()).isEqualTo(QuizRewardCommand.FINGERPRINT_VERSION);
        assertThat(request.getPayloadFingerprint()).isEqualTo(command.computeFingerprint());
        assertThat(request.getCreatedAt()).isEqualTo(NOW);
        assertThat(request.getAppliedAt()).isNull();
    }

    @Test
    void 상태는_V59_CHECK와_같은_6개다() {
        assertThat(QuizRewardRequestStatus.values()).extracting(Enum::name).containsExactly("RECEIVED", "EXECUTING",
                "ACCEPTED", "APPLIED", "UNKNOWN", "RETRYABLE_FAILURE");
    }

    @Test
    void 같은_규칙과_같은_입력이면_같은_요청이고_rewardRequestId는_비교하지_않는다() {
        TicketQuizRewardRequest request = request(command);
        QuizRewardCommand otherUuid = new QuizRewardCommand(100L, 7L, 10L, 3, UUID.randomUUID());
        QuizRewardCommand otherCount = new QuizRewardCommand(100L, 7L, 10L, 2, command.rewardRequestId());

        assertThat(request.hasSamePayload(QuizRewardCommand.FINGERPRINT_VERSION, otherUuid.computeFingerprint()))
                .isTrue();
        assertThat(request.hasSamePayload(QuizRewardCommand.FINGERPRINT_VERSION, otherCount.computeFingerprint()))
                .isFalse();
        assertThat(request.hasSamePayload("quiz-reward-fingerprint-v2", command.computeFingerprint())).isFalse();
    }

    @Test
    void 정답_수가_0_이하이거나_값이_없으면_만들지_않는다() {
        assertThatThrownBy(() -> new TicketQuizRewardRequest("id", 100L, 7L, 10L, 0,
                QuizRewardCommand.FINGERPRINT_VERSION, "f".repeat(64), NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TicketQuizRewardRequest(null, 100L, 7L, 10L, 3,
                QuizRewardCommand.FINGERPRINT_VERSION, "f".repeat(64), NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TicketQuizRewardRequest("id", 100L, 7L, 10L, 3,
                QuizRewardCommand.FINGERPRINT_VERSION, "f".repeat(64), null)).isInstanceOf(NullPointerException.class);
    }

    private static TicketQuizRewardRequest request(QuizRewardCommand command) {
        return new TicketQuizRewardRequest(command.rewardRequestId().toString(), command.attemptId(),
                command.memberId(), command.creatorId(), command.correctCount(), QuizRewardCommand.FINGERPRINT_VERSION,
                command.computeFingerprint(), NOW);
    }
}
