package kr.co.cking.ticket.scheduler;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.lettuce.core.RedisCommandExecutionException;
import kr.co.cking.ticket.application.config.TicketRedisKeys;
import kr.co.cking.ticket.domain.UserTicketBalance;
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
 * (memberId, creatorId) 조합이 연속된 주기에서도 계속 불일치할 때만 경고 로그를 남긴다
 * (검증 시나리오 30번).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketBalanceReconciliationScheduler {

    /** 이 횟수(연속 주기) 이상 반복된 불일치만 지속 불일치로 판단한다. */
    private static final int PERSISTENT_MISMATCH_THRESHOLD = 2;

    private final UserTicketBalanceRepository userTicketBalanceRepository;
    private final StringRedisTemplate redisTemplate;

    private final Map<BalanceKey, Integer> mismatchStreaks = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${cking.ticket.reconciliation-interval-ms:300000}")
    public void reconcile() {
        for (UserTicketBalance balance : userTicketBalanceRepository.findAll()) {
            BalanceKey key = new BalanceKey(balance.getMemberId(), balance.getCreatorId());
            try {
                check(key, balance.getBalance());
            } catch (RedisConnectionFailureException | QueryTimeoutException e) {
                abortCycle(e);
                return;
            } catch (RedisSystemException e) {
                // WRONGTYPE 등 명령 실행 오류(원인이 RedisCommandExecutionException)만 key 단위
                // 문제다. 그 외(연결 종료 등 일반 RedisException)는 Redis 통신 장애로 본다.
                if (e.getCause() instanceof RedisCommandExecutionException) {
                    failKey(key, e);
                } else {
                    abortCycle(e);
                    return;
                }
            } catch (Exception e) {
                failKey(key, e);
            }
        }
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
        String redisValue = redisTemplate.opsForValue().get(TicketRedisKeys.balance(key.creatorId(), key.memberId()));
        if (redisValue == null) {
            // Redis Key 미존재는 별개 이상 상태(BALANCE_NOT_LOADED)다 - 정합성 불일치로 다루지 않는다.
            mismatchStreaks.remove(key);
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
