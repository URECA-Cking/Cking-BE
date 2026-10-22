package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.dto.QuizEarnEvent;
import kr.co.cking.ticket.application.dto.QuizRewardCommand;
import kr.co.cking.ticket.domain.QuizRewardRequestStatus;
import kr.co.cking.ticket.domain.TicketQuizRewardExecution;
import kr.co.cking.ticket.domain.TicketQuizRewardRequest;
import kr.co.cking.ticket.repository.TicketQuizRewardExecutionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class StoredExecutionQuizRewardExecutionPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-12T06:00:00Z");
    private static final UUID REQUEST_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final Long QUIZ_REQUEST_ID = 1L;
    private static final String TOKEN = "00000000-0000-0000-0000-000000000001";

    private final TicketQuizRewardExecutionRepository executionRepository =
            mock(TicketQuizRewardExecutionRepository.class);
    private final StoredExecutionQuizRewardExecutionPolicy policy =
            new StoredExecutionQuizRewardExecutionPolicy(executionRepository);
    private final QuizRewardCommand command = new QuizRewardCommand(100L, 7L, 10L, 3, REQUEST_ID);
    private final QuizEarnEvent event = new QuizEarnEvent(REQUEST_ID, 100L, 7L, 10L, 3, 3L,
            QuizRewardCommand.FINGERPRINT_VERSION, command.computeFingerprint(), TOKEN);

    @Test
    void 이_요청에_저장된_실행권으로_발행됐을_수_있는_메시지만_허용한다() {
        // Worker가 결과를 기록하기 전, Redis 수락 뒤, 응답 유실로 결과를 모를 때
        assertAllowed(QuizRewardRequestStatus.EXECUTING, started());
        assertAllowed(QuizRewardRequestStatus.ACCEPTED, finished("ACCEPTED", null, false));
        assertAllowed(QuizRewardRequestStatus.UNKNOWN, finished("UNKNOWN", "TIMEOUT", false));
        assertAllowed(QuizRewardRequestStatus.UNKNOWN, finished("UNKNOWN", null, false));
        // 결과 기록이 실패해 실행이 결과 없이 남은 경우
        assertAllowed(QuizRewardRequestStatus.EXECUTING, started());
    }

    @Test
    void RECEIVED_RETRYABLE_FAILURE_APPLIED는_거부한다() {
        for (QuizRewardRequestStatus status : List.of(QuizRewardRequestStatus.RECEIVED,
                QuizRewardRequestStatus.RETRYABLE_FAILURE, QuizRewardRequestStatus.APPLIED)) {
            assertRejected(status, started());
        }
    }

    @Test
    void 저장되지_않았거나_다른_요청의_실행권은_거부한다() {
        given(executionRepository.findByExecutionToken(TOKEN)).willReturn(Optional.empty());
        assertThatThrownBy(() -> policy.requireApplicable(request(QuizRewardRequestStatus.EXECUTING), event))
                .isInstanceOf(IllegalStateException.class);

        assertRejected(QuizRewardRequestStatus.EXECUTING,
                TicketQuizRewardExecution.start(QUIZ_REQUEST_ID + 1, 1, TOKEN, NOW));
    }

    @Test
    void 무반영으로_확인된_실행의_실행권은_거부한다() {
        for (String outcome : List.of("RETRYABLE_FAILURE", "BALANCE_NOT_LOADED", "BALANCE_MAINTENANCE",
                "REQUEST_ID_CONFLICT", "INVALID_REQUEST")) {
            assertRejected(QuizRewardRequestStatus.UNKNOWN, finished(outcome, null, true));
            assertRejected(QuizRewardRequestStatus.ACCEPTED, finished(outcome, null, true));
        }
    }

    @Test
    void 이_실행권이_발행하지_않은_결과의_실행권은_거부한다() {
        // ALREADY_ACCEPTED는 guard에 남은 이전 실행권이 발행했다.
        assertRejected(QuizRewardRequestStatus.ACCEPTED, finished("ALREADY_ACCEPTED", null, false));
        // guard 대조에 실패해 UNKNOWN으로 남긴 실행도 이 실행권으로 발행하지 않았다.
        assertRejected(QuizRewardRequestStatus.UNKNOWN,
                finished("UNKNOWN", "ALREADY_ACCEPTED_TOKEN_UNVERIFIED", false));
        assertRejected(QuizRewardRequestStatus.UNKNOWN,
                finished("UNKNOWN", "ALREADY_ACCEPTED_STREAM_MISMATCH", false));
    }

    private void assertAllowed(QuizRewardRequestStatus status, TicketQuizRewardExecution execution) {
        given(executionRepository.findByExecutionToken(TOKEN)).willReturn(Optional.of(execution));
        assertThatCode(() -> policy.requireApplicable(request(status), event)).as(status.name())
                .doesNotThrowAnyException();
    }

    private void assertRejected(QuizRewardRequestStatus status, TicketQuizRewardExecution execution) {
        given(executionRepository.findByExecutionToken(TOKEN)).willReturn(Optional.of(execution));
        assertThatThrownBy(() -> policy.requireApplicable(request(status), event)).as(status.name())
                .isInstanceOf(IllegalStateException.class);
    }

    private TicketQuizRewardRequest request(QuizRewardRequestStatus status) {
        TicketQuizRewardRequest request = new TicketQuizRewardRequest(REQUEST_ID.toString(), 100L, 7L, 10L, 3,
                QuizRewardCommand.FINGERPRINT_VERSION, command.computeFingerprint(), NOW);
        ReflectionTestUtils.setField(request, "quizRewardRequestId", QUIZ_REQUEST_ID);
        ReflectionTestUtils.setField(request, "status", status);
        return request;
    }

    private static TicketQuizRewardExecution started() {
        return TicketQuizRewardExecution.start(QUIZ_REQUEST_ID, 1, TOKEN, NOW);
    }

    private static TicketQuizRewardExecution finished(String outcome, String reason, boolean notExecutedConfirmed) {
        TicketQuizRewardExecution execution = started();
        String streamId = "ACCEPTED".equals(outcome) || "ALREADY_ACCEPTED".equals(outcome) ? "1-0" : null;
        execution.finish(outcome, reason, streamId, notExecutedConfirmed, NOW);
        return execution;
    }
}
