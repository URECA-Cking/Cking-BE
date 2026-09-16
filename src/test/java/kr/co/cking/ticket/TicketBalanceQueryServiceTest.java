package kr.co.cking.ticket;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TicketBalanceQueryServiceTest {

    @Test
    void 잔액이_없으면_0을_반환한다() {
        UserTicketBalanceRepository repository = mock(UserTicketBalanceRepository.class);
        when(repository.findByMemberIdAndCreatorId(1L, 2L)).thenReturn(Optional.empty());
        TicketBalanceQueryService service = new TicketBalanceQueryService(repository);

        long balance = service.getBalance(2L, 1L);

        assertThat(balance).isZero();
    }

    @Test
    void 잔액이_있으면_해당_값을_반환한다() {
        UserTicketBalanceRepository repository = mock(UserTicketBalanceRepository.class);
        UserTicketBalance existing = UserTicketBalance.builder()
                .memberId(1L).creatorId(2L).balance(15L).build();
        when(repository.findByMemberIdAndCreatorId(1L, 2L)).thenReturn(Optional.of(existing));
        TicketBalanceQueryService service = new TicketBalanceQueryService(repository);

        long balance = service.getBalance(2L, 1L);

        assertThat(balance).isEqualTo(15L);
    }
}
