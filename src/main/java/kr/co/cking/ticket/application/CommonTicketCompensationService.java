package kr.co.cking.ticket.application;

import java.time.Instant;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.stream.application.UnappliedBalanceMessageChecker;
import kr.co.cking.ticket.application.config.CommonTicketRedisKeys;
import kr.co.cking.ticket.domain.CommonTicketLedger;
import kr.co.cking.ticket.domain.TicketErrorCode;
import kr.co.cking.ticket.domain.TicketLedgerType;
import kr.co.cking.ticket.domain.UserCommonTicketBalance;
import kr.co.cking.ticket.repository.CommonTicketLedgerRepository;
import kr.co.cking.ticket.repository.UserCommonTicketBalanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link TicketCompensationService}와 같은 계약의 공용 응모권 수동 보정(이슈 #256). DB 잔액을 최종
 * 기준으로 COMPENSATE Ledger를 append-only로 남기고 Redis 잔액을 재동기화한다. 공용 잔액은
 * 크리에이터 축 없이 memberId 하나로 식별한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommonTicketCompensationService {

    private final UserCommonTicketBalanceRepository balanceRepository;
    private final CommonTicketLedgerRepository ledgerRepository;
    private final StringRedisTemplate redisTemplate;
    private final TicketMaintenanceLock maintenanceLock;
    private final UnappliedBalanceMessageChecker unappliedMessageChecker;
    private final TransactionTemplate transactionTemplate;

    public void resyncRedisToDb(Long memberId, String reason) {
        String token = maintenanceLock.acquireCommon(memberId);
        if (token == null) {
            throw new BusinessException(TicketErrorCode.CONCURRENT_COMMAND);
        }
        try {
            if (unappliedMessageChecker.existsCommon(memberId)) {
                throw new BusinessException(TicketErrorCode.INVALID_STATE);
            }
            transactionTemplate.executeWithoutResult(status -> resync(memberId, reason, token));
        } finally {
            maintenanceLock.releaseCommon(memberId, token);
        }
    }

    private void resync(Long memberId, String reason, String token) {
        UserCommonTicketBalance balance = balanceRepository.findByMemberIdForUpdate(memberId)
                .orElseThrow(() -> new IllegalArgumentException("보정 대상 공용 Balance가 없습니다. memberId=" + memberId));

        long dbBalance = balance.getBalance();
        long redisBalanceBefore = readRedisBalance(memberId, dbBalance);

        ledgerRepository.save(
                CommonTicketLedger.builder()
                        .memberId(memberId)
                        .deltaAmount(dbBalance - redisBalanceBefore)
                        .type(TicketLedgerType.COMPENSATE)
                        .reason(reason)
                        .balanceBefore(redisBalanceBefore)
                        .balanceAfter(dbBalance)
                        .createdAt(Instant.now())
                        .build()
        );

        // lock이 만료됐으면 덮어쓰지 않고 예외로 트랜잭션을 롤백한다(COMPENSATE Ledger도 남기지 않는다).
        if (!maintenanceLock.setCommonBalanceIfHeld(memberId, token, dbBalance)) {
            throw new BusinessException(TicketErrorCode.CONCURRENT_COMMAND);
        }
        log.warn("공용 Redis·DB Balance 불일치를 DB 기준으로 재동기화했습니다. memberId={}, redisBalanceBefore={}, dbBalance={}, reason={}",
                memberId, redisBalanceBefore, dbBalance, reason);
    }

    // 키가 없으면 비교 기준이 없으므로 DB 값을 그대로 써서 delta=0으로 기록한다(TicketCompensationService와 동일).
    private long readRedisBalance(Long memberId, long fallback) {
        String value = redisTemplate.opsForValue().get(CommonTicketRedisKeys.balance(memberId));
        if (value == null) {
            log.warn("보정 대상 공용 Redis Balance 키가 없습니다. memberId={}", memberId);
            return fallback;
        }
        return Long.parseLong(value);
    }
}
