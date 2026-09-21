package kr.co.cking.ticket.application;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.event.application.config.EntryRedisKeys;
import kr.co.cking.stream.application.UnappliedBalanceMessageChecker;
import kr.co.cking.ticket.domain.TicketErrorCode;
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
    private final TicketMaintenanceLock maintenanceLock;
    private final UnappliedBalanceMessageChecker unappliedMessageChecker;
    private final TransactionTemplate transactionTemplate;

    /**
     * maintenance lock을 잡고 미반영 SPEND·EARN이 없음을 확인한 뒤에만 보정한다. lock과 검사는 DB 행 잠금을
     * 오래 잡지 않도록 트랜잭션 밖에서 하고, Redis 덮어쓰기는 lock을 아직 소유한 경우에만 수행한다.
     */
    public void resyncRedisToDb(Long memberId, Long creatorId, String reason) {
        String token = maintenanceLock.acquire(creatorId, memberId);
        if (token == null) {
            throw new BusinessException(TicketErrorCode.CONCURRENT_COMMAND);
        }
        try {
            if (unappliedMessageChecker.exists(memberId, creatorId)) {
                throw new BusinessException(TicketErrorCode.INVALID_STATE);
            }
            transactionTemplate.executeWithoutResult(status -> resync(memberId, creatorId, reason, token));
        } finally {
            maintenanceLock.release(creatorId, memberId, token);
        }
    }

    private void resync(Long memberId, Long creatorId, String reason, String token) {
        UserTicketBalance balance = userTicketBalanceRepository
                .findByMemberIdAndCreatorIdForUpdate(memberId, creatorId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "보정 대상 Balance가 없습니다. memberId=%d, creatorId=%d".formatted(memberId, creatorId)));

        long dbBalance = balance.getBalance();
        long redisBalanceBefore = readRedisBalance(memberId, creatorId, dbBalance);

        ticketLedgerRepository.save(
                TicketLedger.builder()
                        .memberId(memberId)
                        .creatorId(creatorId)
                        .deltaAmount(dbBalance - redisBalanceBefore)
                        .type(TicketLedgerType.COMPENSATE)
                        .reason(reason)
                        .balanceBefore(redisBalanceBefore)
                        .balanceAfter(dbBalance)
                        .createdAt(Instant.now())
                        .build()
        );

        // lock이 만료됐으면 덮어쓰지 않고 예외로 트랜잭션을 롤백한다(COMPENSATE Ledger도 남기지 않는다).
        if (!maintenanceLock.setBalanceIfHeld(creatorId, memberId, token, dbBalance)) {
            throw new BusinessException(TicketErrorCode.CONCURRENT_COMMAND);
        }
        log.warn("Redis·DB Balance 불일치를 DB 기준으로 재동기화했습니다. memberId={}, creatorId={}, redisBalanceBefore={}, dbBalance={}, reason={}",
                memberId, creatorId, redisBalanceBefore, dbBalance, reason);
    }

    // 보정 전 Redis 값이 감사기록(balanceBefore)의 핵심이라 덮어쓰기 전에 읽는다.
    // 키 자체가 없는 경우(BALANCE_NOT_LOADED 상황)는 비교 기준이 없으므로 DB 값을
    // 그대로 써서 delta=0으로 기록한다 — "정상"이 아니라 "기준 없음"을 뜻한다.
    private long readRedisBalance(Long memberId, Long creatorId, long fallback) {
        String value = redisTemplate.opsForValue().get(EntryRedisKeys.balance(creatorId, memberId));
        if (value == null) {
            log.warn("보정 대상 Redis Balance 키가 없습니다. memberId={}, creatorId={}", memberId, creatorId);
            return fallback;
        }
        return Long.parseLong(value);
    }
}
