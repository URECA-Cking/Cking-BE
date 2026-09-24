package kr.co.cking.ticket.scheduler;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.lettuce.core.RedisCommandExecutionException;
import kr.co.cking.ticket.application.config.CommonTicketRedisKeys;
import kr.co.cking.ticket.application.config.TicketRedisKeys;
import kr.co.cking.ticket.domain.UserCommonTicketBalance;
import kr.co.cking.ticket.domain.UserTicketBalance;
import kr.co.cking.ticket.repository.UserCommonTicketBalanceRepository;
import kr.co.cking.ticket.repository.UserTicketBalanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis Balance와 DB Balance(Ledger 기반 Projection)를 5분 주기로 비교한다(취합v1.5.4 §13,
 * 요구사항 v4 FR-P2-021~022). 최종 복구 기준은 DB Ledger이며, 이 스케줄러는 감지·기록만 하고
 * 실제 보정({@link kr.co.cking.ticket.application.TicketCompensationService})은 운영자가
 * 확인 후 별도로 호출한다.
 *
 * <p>정상적인 Redis→Stream→DB 비동기 반영 지연을 즉시 오류로 판단하지 않기 위해, 같은
 * (memberId, creatorId) 조합(공용은 creatorId=null)이 연속된 주기에서도 계속 불일치할 때만 경고 로그를 남긴다
 * (검증 시나리오 30번).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketBalanceReconciliationScheduler {

    /** 이 횟수(연속 주기) 이상 반복된 불일치만 지속 불일치로 판단한다. */
    private static final int PERSISTENT_MISMATCH_THRESHOLD = 2;

    /** 한 번에 메모리에 올리는 잔액 행 수. 전량 로드를 피하려는 값이며 성능 튜닝 대상은 아니다. */
    private static final int PAGE_SIZE = 500;

    private final UserTicketBalanceRepository userTicketBalanceRepository;
    private final UserCommonTicketBalanceRepository userCommonTicketBalanceRepository;
    private final StringRedisTemplate redisTemplate;

    private final Map<BalanceKey, Integer> mismatchStreaks = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${cking.ticket.reconciliation-interval-ms:300000}")
    public void reconcile() {
        // Redis 통신 장애로 중단되면(false) 공용 잔액 검사도 이어가지 않는다.
        if (reconcileCreatorBalances()) {
            reconcileCommonBalances();
        }
    }

    private boolean reconcileCreatorBalances() {
        Pageable limit = PageRequest.of(0, PAGE_SIZE);
        Long lastMemberId = Long.MIN_VALUE;
        Long lastCreatorId = Long.MIN_VALUE;
        List<UserTicketBalance> batch;
        do {
            batch = userTicketBalanceRepository.findNextBatch(lastMemberId, lastCreatorId, limit);
            for (UserTicketBalance balance : batch) {
                if (!checkSafely(new BalanceKey(balance.getMemberId(), balance.getCreatorId()), balance.getBalance())) {
                    return false;
                }
            }
            if (!batch.isEmpty()) {
                UserTicketBalance last = batch.get(batch.size() - 1);
                lastMemberId = last.getMemberId();
                lastCreatorId = last.getCreatorId();
            }
        } while (batch.size() == PAGE_SIZE);
        return true;
    }

    // 공용 응모권(이슈 #256): 크리에이터 축이 없어 BalanceKey.creatorId가 null이다.
    private boolean reconcileCommonBalances() {
        Pageable limit = PageRequest.of(0, PAGE_SIZE);
        Long lastMemberId = Long.MIN_VALUE;
        List<UserCommonTicketBalance> batch;
        do {
            batch = userCommonTicketBalanceRepository.findNextBatch(lastMemberId, limit);
            for (UserCommonTicketBalance balance : batch) {
                if (!checkSafely(new BalanceKey(balance.getMemberId(), null), balance.getBalance())) {
                    return false;
                }
            }
            if (!batch.isEmpty()) {
                lastMemberId = batch.get(batch.size() - 1).getMemberId();
            }
        } while (batch.size() == PAGE_SIZE);
        return true;
    }

    /** 이번 주기를 계속할 수 있으면 true, Redis 통신 장애로 중단해야 하면 false. */
    private boolean checkSafely(BalanceKey key, long dbBalance) {
        try {
            check(key, dbBalance);
        } catch (RedisConnectionFailureException | QueryTimeoutException e) {
            abortCycle(e);
            return false;
        } catch (RedisSystemException e) {
            // WRONGTYPE 등 명령 실행 오류(원인이 RedisCommandExecutionException)만 key 단위
            // 문제다. 그 외(연결 종료 등 일반 RedisException)는 Redis 통신 장애로 본다.
            if (e.getCause() instanceof RedisCommandExecutionException) {
                failKey(key, e);
            } else {
                abortCycle(e);
                return false;
            }
        } catch (Exception e) {
            failKey(key, e);
        }
        return true;
    }

    private void abortCycle(Exception e) {
        // Redis 연결/타임아웃 등 통신 장애 - key마다 반복 경고하지 않고 이번 주기를 중단한다.
        // 이번 주기는 비교 자체를 못 했으므로 모든 key의 연속 불일치 스트릭도 초기화한다.
        mismatchStreaks.clear();
        log.warn("Redis 통신 장애로 이번 주기 정합성 검사를 중단합니다.", e);
    }

    private void failKey(BalanceKey key, Exception e) {
        // Redis 값 타입 오류(WRONGTYPE)·숫자 파싱 실패 등 이 key만의 문제는 나머지 Balance
        // 검사를 계속 진행한다. 정상 비교가 끊겼으므로 연속 불일치 스트릭도 초기화한다.
        mismatchStreaks.remove(key);
        log.warn("Redis Balance 조회·파싱에 실패했습니다. memberId={}, creatorId={}",
                key.memberId(), key.creatorId(), e);
    }

    private void check(BalanceKey key, long dbBalance) {
        String redisKey = key.creatorId() == null
                ? CommonTicketRedisKeys.balance(key.memberId())
                : TicketRedisKeys.balance(key.creatorId(), key.memberId());
        String redisValue = redisTemplate.opsForValue().get(redisKey);
        if (redisValue == null) {
            // Redis Key 미존재는 별개 이상 상태(BALANCE_NOT_LOADED)다 - 정합성 불일치로 다루지 않는다.
            mismatchStreaks.remove(key);
            // DB 행은 EARN이 Redis 적립에 성공한 뒤에야 생기므로, 잔액이 있는데 키가 없으면 유실이다.
            // 이 유저는 SPEND에서 계속 BALANCE_NOT_LOADED(503)를 받는다.
            if (dbBalance > 0) {
                log.warn("Redis Balance 키가 없습니다(BALANCE_NOT_LOADED로 응모 불가). memberId={}, creatorId={}, "
                                + "dbBalance={} - TicketCompensationService(공용은 CommonTicketCompensationService).resyncRedisToDb로 복구가 필요합니다.",
                        key.memberId(), key.creatorId(), dbBalance);
            }
            return;
        }

        long redisBalance = Long.parseLong(redisValue);
        if (redisBalance == dbBalance) {
            mismatchStreaks.remove(key);
            return;
        }

        int streak = mismatchStreaks.merge(key, 1, Integer::sum);
        if (streak >= PERSISTENT_MISMATCH_THRESHOLD) {
            log.warn("Redis·DB Balance 불일치가 {}주기 연속 지속되고 있습니다. memberId={}, creatorId={}, "
                            + "redisBalance={}, dbBalance={} - 운영자 확인 및 보정이 필요합니다.",
                    streak, key.memberId(), key.creatorId(), redisBalance, dbBalance);
        } else {
            log.info("Redis·DB Balance 불일치를 감지했습니다(비동기 반영 지연일 수 있음). memberId={}, creatorId={}, "
                            + "redisBalance={}, dbBalance={}",
                    key.memberId(), key.creatorId(), redisBalance, dbBalance);
        }
    }

    private record BalanceKey(Long memberId, Long creatorId) {
    }
}
