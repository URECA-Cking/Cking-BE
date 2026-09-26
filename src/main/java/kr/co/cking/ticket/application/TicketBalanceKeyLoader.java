package kr.co.cking.ticket.application;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import kr.co.cking.stream.application.UnappliedBalanceMessageChecker;
import kr.co.cking.ticket.application.config.CommonTicketRedisKeys;
import kr.co.cking.ticket.application.config.TicketRedisKeys;
import kr.co.cking.ticket.domain.CouponType;
import kr.co.cking.ticket.domain.UserCommonTicketBalance;
import kr.co.cking.ticket.domain.UserTicketBalance;
import kr.co.cking.ticket.repository.UserCommonTicketBalanceRepository;
import kr.co.cking.ticket.repository.UserTicketBalanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 응모가 {@code BALANCE_NOT_LOADED}를 받았을 때 DB 잔액으로 Redis 잔액 키를 적재한다(FR-P2-056).
 * 미반영 SPEND·EARN 메시지가 남은 채 DB 값으로 적재하면 이미 Redis에서 차감한 응모권이 되살아나므로,
 * 미반영 메시지가 없고 보정 락을 잡을 수 있을 때만 적재한다. 락은 수동 보정과 같은 것을 써서 둘이 겹치지 않게 한다.
 * 적재는 {@code SET NX}라 그 사이 EARN이 키를 먼저 만들었다면 덮어쓰지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketBalanceKeyLoader {

    private final StringRedisTemplate redisTemplate;
    private final TicketMaintenanceLock maintenanceLock;
    private final UnappliedBalanceMessageChecker unappliedMessageChecker;
    private final UserTicketBalanceRepository creatorBalanceRepository;
    private final UserCommonTicketBalanceRepository commonBalanceRepository;

    /** 키가 존재하게 됐으면 true(이미 있었던 경우 포함). 조건 불충족이면 false. 락은 반환 전에 푼다. */
    public boolean load(CouponType couponType, Long creatorId, Long memberId) {
        boolean common = couponType == CouponType.COMMON;
        String token = common ? maintenanceLock.acquireCommon(memberId) : maintenanceLock.acquire(creatorId, memberId);
        if (token == null) {
            return false;
        }
        try {
            boolean unapplied = common
                    ? unappliedMessageChecker.existsCommon(memberId)
                    : unappliedMessageChecker.exists(memberId, creatorId);
            if (unapplied) {
                return false;
            }
            // 행이 없으면 받은 적 없는 사용자이므로 0으로 적재해 INSUFFICIENT_BALANCE로 정상 응답하게 한다.
            long balance = common
                    ? commonBalanceRepository.findById(memberId).map(UserCommonTicketBalance::getBalance).orElse(0L)
                    : creatorBalanceRepository.findByMemberIdAndCreatorId(memberId, creatorId)
                            .map(UserTicketBalance::getBalance).orElse(0L);
            String key = common ? CommonTicketRedisKeys.balance(memberId) : TicketRedisKeys.balance(creatorId, memberId);
            redisTemplate.opsForValue().setIfAbsent(key, String.valueOf(balance));
            log.warn("Redis 잔액 키를 DB 기준으로 적재했습니다. couponType={}, memberId={}, creatorId={}, balance={}",
                    couponType, memberId, creatorId, balance);
            return true;
        } catch (DataAccessException e) {
            // 적재는 최선 노력이다. Redis·DB 오류를 응모 500으로 키우지 않고 기존 BALANCE_NOT_LOADED(503)로 남긴다.
            log.error("Redis 잔액 키 적재 중 오류가 발생했습니다. couponType={}, memberId={}, creatorId={}",
                    couponType, memberId, creatorId, e);
            return false;
        } finally {
            if (common) {
                maintenanceLock.releaseCommon(memberId, token);
            } else {
                maintenanceLock.release(creatorId, memberId, token);
            }
        }
    }
}
