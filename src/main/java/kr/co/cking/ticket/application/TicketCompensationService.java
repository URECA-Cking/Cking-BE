package kr.co.cking.ticket.application;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import kr.co.cking.event.application.config.EntryRedisKeys;
import kr.co.cking.ticket.domain.TicketLedger;
import kr.co.cking.ticket.domain.TicketLedgerType;
import kr.co.cking.ticket.domain.UserTicketBalance;
import kr.co.cking.ticket.repository.TicketLedgerRepository;
import kr.co.cking.ticket.repository.UserTicketBalanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 지속적인 Redis·DB 잔액 불일치가 확인된 경우에만 쓰는 수동 보정 경로다(통합 API
 * 명세 v2.5 §9.6, 요구사항 v4 FR-14a). 기존 Ledger는 수정·삭제하지 않고 DB 잔액을
 * 최종 기준으로 삼아 COMPENSATE Ledger를 append-only로 남긴 뒤 Redis 잔액을
 * DB 값으로 재동기화한다 — 일반적인 5분 주기 자동 배치와 별개로, 운영자가 확인한
 * 개별 케이스에 직접 호출하는 용도다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketCompensationService {

    private final UserTicketBalanceRepository userTicketBalanceRepository;
    private final TicketLedgerRepository ticketLedgerRepository;
    private final StringRedisTemplate redisTemplate;

    @Transactional
    public void resyncRedisToDb(Long memberId, Long creatorId, String reason) {
        UserTicketBalance balance = userTicketBalanceRepository
                .findByMemberIdAndCreatorIdForUpdate(memberId, creatorId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "보정 대상 Balance가 없습니다. memberId=%d, creatorId=%d".formatted(memberId, creatorId)));

        long dbBalance = balance.getBalance();

        ticketLedgerRepository.save(
                TicketLedger.builder()
                        .memberId(memberId)
                        .creatorId(creatorId)
                        .deltaAmount(0L)
                        .type(TicketLedgerType.COMPENSATE)
                        .reason(reason)
                        .balanceBefore(dbBalance)
                        .balanceAfter(dbBalance)
                        .createdAt(Instant.now())
                        .build()
        );

        redisTemplate.opsForValue().set(EntryRedisKeys.balance(creatorId, memberId), String.valueOf(dbBalance));
        log.warn("Redis·DB Balance 불일치를 DB 기준으로 재동기화했습니다. memberId={}, creatorId={}, balance={}, reason={}",
                memberId, creatorId, dbBalance, reason);
    }
}
