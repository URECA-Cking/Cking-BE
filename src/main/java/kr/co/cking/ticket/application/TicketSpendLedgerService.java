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
import kr.co.cking.ticket.domain.CommonTicketLedger;
import kr.co.cking.ticket.domain.CouponType;
import kr.co.cking.ticket.domain.TicketLedger;
import kr.co.cking.ticket.domain.TicketLedgerType;
import kr.co.cking.ticket.domain.UserCommonTicketBalance;
import kr.co.cking.ticket.domain.UserTicketBalance;
import kr.co.cking.ticket.repository.CommonTicketLedgerRepository;
import kr.co.cking.ticket.repository.TicketLedgerRepository;
import kr.co.cking.ticket.repository.UserCommonTicketBalanceRepository;
import kr.co.cking.ticket.repository.UserTicketBalanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * SPEND Stream Consumer가 호출하는 DB 반영 로직. EventEntry INSERT → SPEND Ledger
 * INSERT → Balance UPDATE를 한 트랜잭션으로 묶는다(통합 API 명세 v2.5 §9.3).
 * 호출자(Consumer)는 이 메서드가 예외 없이 반환한 뒤에만 XACK한다.
 *
 * <p>{@code couponType}에 따라 크리에이터 전용({@link TicketLedger}/{@link UserTicketBalance})과
 * 공용({@link CommonTicketLedger}/{@link UserCommonTicketBalance}, 이슈 #219/#224) 중 어느
 * 잔액·Ledger를 갱신할지 분기한다(이슈 #243). {@code EventEntry}는 응모권 종류와 무관하게
 * 단일 테이블을 공유한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketSpendLedgerService {

    private final EventRepository eventRepository;
    private final EventEntryRepository eventEntryRepository;
    private final TicketLedgerRepository ticketLedgerRepository;
    private final UserTicketBalanceRepository userTicketBalanceRepository;
    private final CommonTicketLedgerRepository commonTicketLedgerRepository;
    private final UserCommonTicketBalanceRepository userCommonTicketBalanceRepository;

    @Transactional
    public void apply(SpendCommand command) {
        String requestId = command.requestId();

        // 교차 정합성(CLAUDE.md §11): SPEND Ledger.creator_id는 Event.creator_id와
        // 같아야 한다 — 타 Creator 응모권 오용 방지 핵심. event_entry가 creatorId를
        // 저장하지 않아 재전달 분기(멱등 확인)에서는 비교할 데이터가 없으므로, 이
        // 검증은 신규/재전달 여부와 무관하게 모든 요청에 대해 먼저 수행한다(PR #87
        // 리뷰 반영 — 이전에는 재전달 분기가 eventId/userId/ticketCount만 비교해서
        // 동일 requestId에 creatorId만 다른 페이로드를 검증 없이 통과시켰다). couponType이
        // COMMON이어도 command.creatorId()는 Event에서 resolve된 값 그대로이므로 이
        // 검증은 그대로 적용된다.
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

        if (command.couponType() == CouponType.COMMON) {
            applyCommon(command, entry, now);
        } else {
            applyCreator(command, entry, now);
        }
    }

    private void applyCreator(SpendCommand command, EventEntry entry, Instant now) {
        String requestId = command.requestId();

        UserTicketBalance balance = userTicketBalanceRepository
                .findByMemberIdAndCreatorIdForUpdate(command.userId(), command.creatorId())
                .orElseThrow(() -> new IllegalStateException(
                        "차감 대상 Balance가 없습니다. userId=%d, creatorId=%d, requestId=%s"
                                .formatted(command.userId(), command.creatorId(), requestId)));

        long balanceBefore = balance.getBalance();
        long delta = -command.ticketCount();
        balance.applyDelta(delta, now);

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

    // 공용 응모권(크리에이터 무관, 이슈 #219/#224) SPEND 반영. 잔액이 없으면(공용 응모권을
    // 한 번도 받은 적 없는 사용자) 여기까지 올 수 없다 — Redis Lua가 BALANCE_NOT_LOADED로
    // 먼저 막았어야 한다. 그래도 없으면 메시지를 PEL에 남겨 재시도하게 예외를 던진다.
    private void applyCommon(SpendCommand command, EventEntry entry, Instant now) {
        String requestId = command.requestId();

        UserCommonTicketBalance balance = userCommonTicketBalanceRepository
                .findByMemberIdForUpdate(command.userId())
                .orElseThrow(() -> new IllegalStateException(
                        "차감 대상 공용 Balance가 없습니다. userId=%d, requestId=%s"
                                .formatted(command.userId(), requestId)));

        long balanceBefore = balance.getBalance();
        long delta = -command.ticketCount();
        balance.applyDelta(delta, now);

        commonTicketLedgerRepository.save(
                CommonTicketLedger.builder()
                        .memberId(command.userId())
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
    // 판단하지 않고, 기존 저장 데이터와 내용(eventId/memberId/ticketCount/couponType)까지
    // 일치하는 경우에만 정상 재전달로 인정한다. EventEntry는 couponType을 저장하지 않으므로
    // (이슈 #243) 실제로 어느 쪽에 반영됐는지는 Ledger 존재 여부로 역산한다 - Lua fingerprint가
    // couponType별로 달라 정상 경로에서는 이 불일치가 나올 수 없지만, 수동 Dead Stream replay
    // 같은 우회 경로에 대한 방어선으로 남긴다.
    private void verifySameRequest(EventEntry existing, SpendCommand command) {
        boolean same = existing.getEventId().equals(command.eventId())
                && existing.getMemberId().equals(command.userId())
                && existing.getUsedTicketCount().equals(command.ticketCount())
                && resolveCouponType(existing.getEntryId()) == command.couponType();

        if (!same) {
            throw new IllegalStateException(
                    "동일 requestId에 다른 요청 내용이 감지됐습니다. requestId=%s".formatted(command.requestId()));
        }
    }

    // PR #247 리뷰(자비): requestId로 조회하면 오판할 수 있다 — common_ticket_ledger에는
    // 공용 미션 EARN 행도 쌓이고 requestId는 클라이언트가 API마다 독립적으로 생성하는 값이라
    // 이 테이블 안에서만 유일할 뿐, EARN이 쓴 requestId를 다른 CREATOR SPEND가 재사용하면
    // requestId 기준 조회는 그 EARN 행을 찾아 COMMON으로 잘못 판정한다. eventEntryId는
    // SPEND 전용이라(EARN은 event_entry와 무관) 이걸로 판정해야 안전하다.
    private CouponType resolveCouponType(Long eventEntryId) {
        return commonTicketLedgerRepository.existsByEventEntryId(eventEntryId)
                ? CouponType.COMMON
                : CouponType.CREATOR;
    }
}
