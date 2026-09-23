package kr.co.cking.ticket.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.ticket.application.dto.CommonTicketBalanceResponse;
import kr.co.cking.ticket.application.dto.CommonTicketLedgerItemResponse;
import kr.co.cking.ticket.application.dto.CommonTicketLedgerPage;
import kr.co.cking.ticket.repository.CommonTicketLedgerRepository;
import kr.co.cking.ticket.repository.CommonTicketLedgerView;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/** {@link TicketQueryService}와 동일 계약의 공용 응모권 잔액·이력 조회(이슈 #219). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommonTicketQueryService {

    private final CommonTicketBalanceQueryService balanceQueryService;
    private final CommonTicketLedgerRepository ledgerRepository;

    public CommonTicketBalanceResponse getBalance(Long memberId) {
        return balanceQueryService.getBalanceDetail(memberId);
    }

    public CommonTicketLedgerPage getLedger(Long memberId, int size, String cursor) {
        List<CommonTicketLedgerView> ledgers;
        if (cursor == null || cursor.isBlank()) {
            ledgers = ledgerRepository.findFirstPageView(memberId, PageRequest.of(0, size + 1));
        } else {
            Cursor decoded = Cursor.decode(cursor);
            ledgers = ledgerRepository.findAfterCursorView(memberId, decoded.createdAt(), decoded.ledgerId(),
                    PageRequest.of(0, size + 1));
        }

        boolean hasNext = ledgers.size() > size;
        List<CommonTicketLedgerView> page = hasNext ? ledgers.subList(0, size) : ledgers;
        String nextCursor = hasNext ? Cursor.encode(page.get(page.size() - 1)) : null;
        return new CommonTicketLedgerPage(memberId,
                page.stream().map(CommonTicketLedgerItemResponse::from).toList(), nextCursor, hasNext);
    }

    private record Cursor(Instant createdAt, Long ledgerId) {
        private static String encode(CommonTicketLedgerView ledger) {
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
