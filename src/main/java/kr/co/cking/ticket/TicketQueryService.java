package kr.co.cking.ticket;

import lombok.RequiredArgsConstructor;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TicketQueryService {

    private final UserTicketBalanceRepository balanceRepository;
    private final TicketLedgerRepository ledgerRepository;

    public TicketBalanceResponse getBalance(Long creatorId, Long memberId) {
        return balanceRepository.findByMemberIdAndCreatorId(memberId, creatorId)
                .map(balance -> new TicketBalanceResponse(memberId, creatorId, balance.getBalance(), balance.getUpdatedAt()))
                .orElseGet(() -> new TicketBalanceResponse(memberId, creatorId, 0L, null));
    }

    public TicketLedgerPage getLedger(Long creatorId, Long memberId, int size, String cursor) {
        List<TicketLedgerView> ledgers;
        if (cursor == null || cursor.isBlank()) {
            ledgers = ledgerRepository.findFirstPageView(memberId, creatorId, PageRequest.of(0, size + 1));
        } else {
            Cursor decoded = Cursor.decode(cursor);
            ledgers = ledgerRepository.findAfterCursorView(memberId, creatorId, decoded.createdAt(), decoded.ledgerId(),
                    PageRequest.of(0, size + 1));
        }

        boolean hasNext = ledgers.size() > size;
        List<TicketLedgerView> page = hasNext ? ledgers.subList(0, size) : ledgers;
        String nextCursor = hasNext ? Cursor.encode(page.get(page.size() - 1)) : null;
        return new TicketLedgerPage(memberId, creatorId,
                page.stream().map(TicketLedgerItemResponse::from).toList(), nextCursor, hasNext);
    }

    private record Cursor(Instant createdAt, Long ledgerId) {
        private static String encode(TicketLedgerView ledger) {
            String raw = ledger.getCreatedAt().toString() + "|" + ledger.getLedgerId();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        private static Cursor decode(String value) {
            try {
                String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
                String[] parts = raw.split("\\|", -1);
                if (parts.length != 2) {
                    throw new IllegalArgumentException();
                }
                return new Cursor(Instant.parse(parts[0]), Long.parseLong(parts[1]));
            } catch (RuntimeException e) {
                throw new BusinessException(CommonErrorCode.VALIDATION_FAILED, "cursor: 올바르지 않은 커서입니다.");
            }
        }
    }
}
