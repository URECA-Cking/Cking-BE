package kr.co.cking.ticket;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TicketQueryServiceTest {

    private final UserTicketBalanceRepository balanceRepository = mock(UserTicketBalanceRepository.class);
    private final TicketLedgerRepository ledgerRepository = mock(TicketLedgerRepository.class);
    private final TicketQueryService service = new TicketQueryService(balanceRepository, ledgerRepository);

    @Test
    void balance는_member와_creator기준으로_조회한다() {
        UserTicketBalance balance = UserTicketBalance.builder()
                .memberId(1L)
                .creatorId(2L)
                .balance(15L)
                .updatedAt(Instant.parse("2026-09-16T00:00:00Z"))
                .build();
        when(balanceRepository.findByMemberIdAndCreatorId(1L, 2L)).thenReturn(Optional.of(balance));

        TicketBalanceResponse result = service.getBalance(2L, 1L);

        assertThat(result.userId()).isEqualTo(1L);
        assertThat(result.creatorId()).isEqualTo(2L);
        assertThat(result.balance()).isEqualTo(15L);
        assertThat(result.updatedAt()).isEqualTo(Instant.parse("2026-09-16T00:00:00Z"));
    }

    @Test
    void balance가_없으면_0으로_반환한다() {
        when(balanceRepository.findByMemberIdAndCreatorId(1L, 2L)).thenReturn(Optional.empty());

        TicketBalanceResponse result = service.getBalance(2L, 1L);

        assertThat(result.balance()).isZero();
        assertThat(result.updatedAt()).isNull();
    }

    @Test
    void ledger는_creator별_최신순으로_조회하고_cursor를_반환한다() {
        TicketLedgerView first = view(10L, 3L, "EARN", "2026-09-16T02:00:00Z");
        TicketLedgerView second = view(9L, -1L, "SPEND", "2026-09-16T01:00:00Z");
        TicketLedgerView third = view(8L, 2L, "EARN", "2026-09-16T00:00:00Z");
        when(ledgerRepository.findFirstPageView(1L, 2L, org.springframework.data.domain.PageRequest.of(0, 3)))
                .thenReturn(List.of(first, second, third));

        TicketLedgerPage result = service.getLedger(2L, 1L, 2, null);

        assertThat(result.userId()).isEqualTo(1L);
        assertThat(result.creatorId()).isEqualTo(2L);

        assertThat(result.items()).extracting(TicketLedgerItemResponse::ledgerId)
                .containsExactly(10L, 9L);
        assertThat(result.items()).extracting(TicketLedgerItemResponse::deltaAmount)
                .containsExactly(3L, -1L);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.nextCursor()).isNotBlank();

        when(ledgerRepository.findAfterCursorView(
                1L, 2L, second.getCreatedAt(), second.getLedgerId(),
                org.springframework.data.domain.PageRequest.of(0, 3)))
                .thenReturn(List.of(third));
        TicketLedgerPage next = service.getLedger(2L, 1L, 2, result.nextCursor());

        assertThat(next.items()).extracting(TicketLedgerItemResponse::ledgerId)
                .containsExactly(8L);
        assertThat(next.hasNext()).isFalse();
        assertThat(next.nextCursor()).isNull();
    }

    private TicketLedgerView view(Long ledgerId, Long deltaAmount, String type, String createdAt) {
        TicketLedgerView view = mock(TicketLedgerView.class);
        when(view.getLedgerId()).thenReturn(ledgerId);
        when(view.getDeltaAmount()).thenReturn(deltaAmount);
        when(view.getType()).thenReturn(type);
        when(view.getCreatedAt()).thenReturn(Instant.parse(createdAt));
        return view;
    }

    @Test
    void 잘못된_cursor는_validation오류로_실패한다() {
        assertThatThrownBy(() -> service.getLedger(2L, 1L, 20, "invalid"))
                .isInstanceOf(kr.co.cking.common.exception.BusinessException.class)
                .hasMessageContaining("올바르지 않은 커서");
    }
}
