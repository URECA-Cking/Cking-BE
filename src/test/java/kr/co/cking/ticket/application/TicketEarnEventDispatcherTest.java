package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.dto.EarnCommand;
import kr.co.cking.ticket.application.dto.EarnRewardPolicy;
import kr.co.cking.ticket.application.dto.QuizEarnEvent;
import kr.co.cking.ticket.application.dto.QuizRewardCommand;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class TicketEarnEventDispatcherTest {

    private static final UUID REQUEST_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    private final TicketEarnLedgerService missionLedgerService = mock(TicketEarnLedgerService.class);
    private final QuizRewardLedgerService quizRewardLedgerService = mock(QuizRewardLedgerService.class);
    private final TicketEarnEventDispatcher dispatcher =
            new TicketEarnEventDispatcher(missionLedgerService, quizRewardLedgerService);

    @Test
    void source와_버전이_없는_기존_메시지는_지금처럼_Mission으로_반영한다() {
        dispatcher.apply(missionFields());

        ArgumentCaptor<EarnCommand> command = ArgumentCaptor.forClass(EarnCommand.class);
        verify(missionLedgerService).apply(command.capture());
        assertThat(command.getValue()).isEqualTo(EarnCommand.fromStreamFields(missionFields()));
        assertThat(command.getValue().rewardPolicy()).isEqualTo(EarnRewardPolicy.ONCE);
        verifyNoInteractions(quizRewardLedgerService);
    }

    @Test
    void 명시적_MISSION은_지원하는_버전만_Mission으로_반영한다() {
        Map<String, String> fields = missionFields();
        fields.put("sourceType", "MISSION");
        fields.put("payloadVersion", "1");
        dispatcher.apply(fields);
        verify(missionLedgerService).apply(any(EarnCommand.class));

        for (String version : new String[] {null, "2", "0"}) {
            Map<String, String> unsupported = missionFields();
            unsupported.put("sourceType", "MISSION");
            unsupported.put("payloadVersion", version);
            assertThatThrownBy(() -> dispatcher.apply(unsupported)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(quizRewardLedgerService);
    }

    @Test
    void QUIZ_v1은_Quiz_전용으로_반영하고_Mission으로_보내지_않는다() {
        dispatcher.apply(quizFields());

        ArgumentCaptor<QuizEarnEvent> event = ArgumentCaptor.forClass(QuizEarnEvent.class);
        verify(quizRewardLedgerService).apply(event.capture());
        assertThat(event.getValue().requestId()).isEqualTo(REQUEST_ID);
        verify(missionLedgerService, never()).apply(any());
    }

    @Test
    void 알_수_없는_source나_버전은_어느_쪽에도_반영하지_않는다() {
        Map<String, String> quizV2 = quizFields();
        quizV2.put("payloadVersion", "2");
        Map<String, String> unknownSource = quizFields();
        unknownSource.put("sourceType", "POST");
        Map<String, String> versionOnly = missionFields();
        versionOnly.put("payloadVersion", "1");
        Map<String, String> invalidQuiz = quizFields();
        invalidQuiz.put("amount", "9");

        for (Map<String, String> fields : new Map[] {quizV2, unknownSource, versionOnly, invalidQuiz}) {
            assertThatThrownBy(() -> dispatcher.apply(fields)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(missionLedgerService, quizRewardLedgerService);
    }

    @Test
    void source가_없는_Quiz_필드_메시지는_Mission_필드가_없어_Mission으로_반영되지_않는다() {
        Map<String, String> fields = quizFields();
        fields.remove("sourceType");
        fields.remove("payloadVersion");

        assertThatThrownBy(() -> dispatcher.apply(fields)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(quizRewardLedgerService);
        verify(missionLedgerService, never()).apply(any());
    }

    private static Map<String, String> missionFields() {
        Map<String, String> fields = new HashMap<>();
        fields.put("requestId", REQUEST_ID.toString());
        fields.put("userId", "7");
        fields.put("creatorId", "10");
        fields.put("missionType", "LIKE");
        fields.put("missionId", "3");
        fields.put("periodKey", "2026-10-12");
        fields.put("missionKey", "like:1");
        fields.put("amount", "1");
        fields.put("rewardPolicy", "ONCE");
        return fields;
    }

    private static Map<String, String> quizFields() {
        QuizRewardCommand command = new QuizRewardCommand(100L, 7L, 10L, 3, REQUEST_ID);
        return new HashMap<>(new QuizEarnEvent(REQUEST_ID, 100L, 7L, 10L, 3, 3L, QuizRewardCommand.FINGERPRINT_VERSION,
                command.computeFingerprint(), "token-1").toStreamFields());
    }
}
