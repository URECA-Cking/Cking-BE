package kr.co.cking.stream.application;

import kr.co.cking.stream.domain.DeadStreamMessage;
import kr.co.cking.stream.domain.DeadStreamType;
import kr.co.cking.stream.repository.DeadStreamMessageRepository;
import kr.co.cking.ticket.application.CommonEarnEventDispatcher;
import kr.co.cking.ticket.application.TicketEarnEventDispatcher;
import kr.co.cking.ticket.application.TicketSpendLedgerService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class DeadStreamReplayServiceTest {

    private final DeadStreamMessageRepository repository = mock(DeadStreamMessageRepository.class);
    private final TicketEarnEventDispatcher dispatcher = mock(TicketEarnEventDispatcher.class);
    private final TicketSpendLedgerService spendLedgerService = mock(TicketSpendLedgerService.class);
    private final CommonEarnEventDispatcher commonLedgerService = mock(CommonEarnEventDispatcher.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DeadStreamReplayService service = new DeadStreamReplayService(repository, dispatcher,
            spendLedgerService, commonLedgerService, objectMapper);
    private final Map<String, String> fields = Map.of("sourceType", "QUIZ", "payloadVersion", "1", "requestId", "r");

    @Test
    void EARN_메시지는_정상_소비와_같은_분기로_반영한_뒤_RESOLVED로_표시한다() {
        DeadStreamMessage message = earnMessage();

        service.replay(1L, 9L);

        verify(dispatcher).apply(fields);
        verify(message).resolve(org.mockito.ArgumentMatchers.eq(9L), any());
        verifyNoInteractions(spendLedgerService, commonLedgerService);
    }

    @Test
    void 분기가_거부한_EARN_메시지는_RESOLVED로_표시하지_않는다() {
        DeadStreamMessage message = earnMessage();
        willThrow(new IllegalArgumentException("unknown source")).given(dispatcher).apply(fields);

        assertThatThrownBy(() -> service.replay(1L, 9L)).isInstanceOf(IllegalArgumentException.class);

        verify(message, never()).resolve(anyLong(), any());
    }

    private DeadStreamMessage earnMessage() {
        DeadStreamMessage message = mock(DeadStreamMessage.class);
        given(message.isUnresolved()).willReturn(true);
        given(message.getStreamType()).willReturn(DeadStreamType.EARN);
        given(message.getPayload()).willReturn(objectMapper.writeValueAsString(fields));
        given(repository.findByIdForUpdate(1L)).willReturn(Optional.of(message));
        return message;
    }
}
