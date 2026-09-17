package kr.co.cking.ticket.application;

import java.time.Instant;
import java.util.Optional;

import kr.co.cking.ticket.TicketBalanceResponse;
import kr.co.cking.ticket.domain.UserTicketBalance;
import kr.co.cking.ticket.repository.UserTicketBalanceRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TicketBalanceQueryServiceTest {

    private final UserTicketBalanceRepository repository = mock(UserTicketBalanceRepository.class);
    private final TicketBalanceQueryService service = new TicketBalanceQueryService(repository);

    @Test
    void 잔액이_없으면_0을_반환한다() {
        when(repository.findByMemberIdAndCreatorId(1L, 2L)).thenReturn(Optional.empty());

        long balance = service.getBalance(2L, 1L);

        assertThat(balance).isZero();
    }

    @Test
    void 잔액이_있으면_해당_값을_반환한다() {
        UserTicketBalance existing = UserTicketBalance.builder()
                .memberId(1L)
                .creatorId(2L)
                .balance(15L)
                .build();
        when(repository.findByMemberIdAndCreatorId(1L, 2L)).thenReturn(Optional.of(existing));

        long balance = service.getBalance(2L, 1L);

        assertThat(balance).isEqualTo(15L);
    }

    @Test
    void 잔액_상세조회는_기존_잔액과_갱신시각을_반환한다() {
        Instant updatedAt = Instant.parse("2026-09-16T00:00:00Z");
        UserTicketBalance balance = UserTicketBalance.builder()
                .memberId(1L)
                .creatorId(2L)
                .balance(15L)
                .updatedAt(updatedAt)
                .build();
        when(repository.findByMemberIdAndCreatorId(1L, 2L)).thenReturn(Optional.of(balance));

        TicketBalanceResponse result = service.getBalanceDetail(2L, 1L);

        assertThat(result).isEqualTo(new TicketBalanceResponse(1L, 2L, 15L, updatedAt));
    }

}
