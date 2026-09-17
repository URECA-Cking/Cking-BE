package kr.co.cking.ticket.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventEntry;
import kr.co.cking.event.repository.EventEntryRepository;
import kr.co.cking.event.repository.EventRepository;
import kr.co.cking.ticket.application.dto.SpendCommand;
import kr.co.cking.ticket.domain.TicketLedger;
import kr.co.cking.ticket.domain.TicketLedgerType;
import kr.co.cking.ticket.domain.UserTicketBalance;
import kr.co.cking.ticket.repository.TicketLedgerRepository;
import kr.co.cking.ticket.repository.UserTicketBalanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * SPEND Stream Consumer가 호출하는 DB 반영 로직. EventEntry INSERT → SPEND Ledger
 * INSERT → Balance UPDATE를 한 트랜잭션으로 묶는다(통합 API 명세 v2.5 §9.3).
 * 호출자(Consumer)는 이 메서드가 예외 없이 반환한 뒤에만 XACK한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketSpendLedgerService {

    private final EventRepository eventRepository;
    private final EventEntryRepository eventEntryRepository;
    private final TicketLedgerRepository ticketLedgerRepository;
    private final UserTicketBalanceRepository userTicketBalanceRepository;

    @Transactional
    public void apply(SpendCommand command) {
        String requestId = command.requestId();

        // 교차 정합성(CLAUDE.md §11): SPEND Ledger.creator_id는 Event.creator_id와
        // 같아야 한다 — 타 Creator 응모권 오용 방지 핵심. event_entry가 creatorId를
        // 저장하지 않아 재전달 분기(멱등 확인)에서는 비교할 데이터가 없으므로, 이
        // 검증은 신규/재전달 여부와 무관하게 모든 요청에 대해 먼저 수행한다(PR #87
        // 리뷰 반영 — 이전에는 재전달 분기가 eventId/userId/ticketCount만 비교해서
        // 동일 requestId에 creatorId만 다른 페이로드를 검증 없이 통과시켰다).
        Event event = eventRepository.findById(command.eventId())
                .orElseThrow(() -> new IllegalStateException(
                        "eventId가 존재하지 않습니다. eventId=%d, requestId=%s"
                                .formatted(command.eventId(), requestId)));
        if (!event.getCreatorId().equals(command.creatorId())) {
            throw new IllegalStateException(
                    "creatorId가 Event.creatorId와 다릅니다. eventId=%d, streamCreatorId=%d, eventCreatorId=%d, requestId=%s"
                            .formatted(command.eventId(), command.creatorId(), event.getCreatorId(), requestId));
        }

        Optional<EventEntry> existing = eventEntryRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            verifySameRequest(existing.get(), command);
            log.info("이미 반영된 SPEND 요청이라 재차감하지 않습니다. requestId={}", requestId);
            return;
        }

        Instant now = Instant.now();

        UserTicketBalance balance = userTicketBalanceRepository
                .findByMemberIdAndCreatorIdForUpdate(command.userId(), command.creatorId())
                .orElseThrow(() -> new IllegalStateException(
                        "차감 대상 Balance가 없습니다. userId=%d, creatorId=%d, requestId=%s"
                                .formatted(command.userId(), command.creatorId(), requestId)));

        long balanceBefore = balance.getBalance();
        if (balanceBefore < command.ticketCount()) {
            throw new IllegalStateException(
                    "Redis 승인 이후 DB 잔액 정합성 위반이 감지됐습니다. dbBalance=%d, ticketCount=%d, userId=%d, creatorId=%d, requestId=%s"
                            .formatted(balanceBefore, command.ticketCount(), command.userId(), command.creatorId(), requestId));
        }
        long delta = -command.ticketCount();
        balance.applyDelta(delta, now);

        // uk_entry_request 충돌(진짜 동시 재전달 경합)이 나면 여기서 복구를 시도하지
        // 않고 그대로 던져서 트랜잭션을 롤백한다 — TicketEarnLedgerService와 동일한
        // 이유(오염된 Hibernate 세션에서 재조회 시 AssertionFailure).
        EventEntry entry = eventEntryRepository.save(
                EventEntry.builder()
                        .memberId(command.userId())
                        .eventId(command.eventId())
                        .requestId(requestId)
                        .usedTicketCount(command.ticketCount())
                        .appliedAt(now)
                        .build()
        );

        ticketLedgerRepository.save(
                TicketLedger.builder()
                        .memberId(command.userId())
                        .creatorId(command.creatorId())
                        .eventEntryId(entry.getEntryId())
                        .deltaAmount(delta)
                        .type(TicketLedgerType.SPEND)
                        .requestId(requestId)
                        .balanceBefore(balanceBefore)
                        .balanceAfter(balance.getBalance())
                        .createdAt(now)
                        .build()
        );
    }

    // 같은 requestId라도 payload가 다르면 다른 요청이다 — 존재 여부만으로 멱등 재처리를
    // 판단하지 않고, 기존 저장 데이터와 내용(eventId/memberId/ticketCount)까지 일치하는
    // 경우에만 정상 재전달로 인정한다.
    private void verifySameRequest(EventEntry existing, SpendCommand command) {
        boolean same = existing.getEventId().equals(command.eventId())
                && existing.getMemberId().equals(command.userId())
                && existing.getUsedTicketCount().equals(command.ticketCount());

        if (!same) {
            throw new IllegalStateException(
                    "동일 requestId에 다른 요청 내용이 감지됐습니다. requestId=%s".formatted(command.requestId()));
        }
    }
}
