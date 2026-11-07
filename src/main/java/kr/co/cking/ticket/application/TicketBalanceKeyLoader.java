package kr.co.cking.ticket.application;

import java.util.List;
import java.util.function.Function;

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
 * 미반영 메시지 검사가 길어져 락이 만료될 수 있으므로, 락 토큰 확인과 {@code SET NX}는
 * {@link TicketMaintenanceLock#loadBalanceIfHeld}로 원자 처리한다 - 만료됐으면 적재하지 않고 실패로 끝낸다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketBalanceKeyLoader {

    private final TicketMaintenanceLock maintenanceLock;
    private final UnappliedBalanceMessageChecker unappliedMessageChecker;
    private final UserTicketBalanceRepository creatorBalanceRepository;
    private final UserCommonTicketBalanceRepository commonBalanceRepository;
    private final StringRedisTemplate redisTemplate;

    /** 적립 Lua가 돌려주는 "잔액 키 없음" 결과 코드(#793). Lua 밖으로 노출하지 않는다. */
    public static final String EARN_BALANCE_NOT_LOADED = "BALANCE_NOT_LOADED";

    /** 적립 직전 잔액 키 상태. Lua의 requireBalance 인자를 정한다. */
    public enum EarnBalance {
        /** DB 잔액 행이 없는 최초 적립. Lua가 없는 키를 0에서 시작해도 된다(취합v1.5.4 §2.4). */
        NEW,
        /** 잔액이 있던 사용자. Lua는 키가 있을 때만 적립해야 한다. */
        EXISTING,
        /** 키를 준비하지 못했다(미반영 메시지·보정 락·저장소 오류). 아무것도 쓰지 않았다. */
        NOT_READY
    }

    /**
     * 적립 Lua를 실행하기 전에 잔액 키를 준비한다(#793). 적립 Lua는 키가 없으면 0에서 시작하므로(최초 적립 예외), 키가
     * 유실된 사용자가 그대로 적립하면 Redis 잔액이 DB보다 작게 굳고 {@link #load}의 {@code SET NX}로도 고칠 수 없다.
     *
     * <ul>
     *   <li>키가 있으면 {@link EarnBalance#EXISTING}. 확인 뒤 키가 사라져도 Lua가 원자적으로 막는다.</li>
     *   <li>키도 DB 잔액 행도 없으면 {@link EarnBalance#NEW}. 첫 미션마다 미반영 메시지를 훑지 않으려는 것이다.</li>
     *   <li>DB 행이 있는데 키가 없으면 유실이므로 {@link #load}로 DB 값을 먼저 적재한다.</li>
     * </ul>
     */
    public EarnBalance prepareForEarn(CouponType couponType, Long creatorId, Long memberId) {
        boolean common = couponType == CouponType.COMMON;
        try {
            String balanceKey = common ? CommonTicketRedisKeys.balance(memberId) : TicketRedisKeys.balance(creatorId, memberId);
            if (Boolean.TRUE.equals(redisTemplate.hasKey(balanceKey))) {
                return EarnBalance.EXISTING;
            }
            boolean recorded = common
                    ? commonBalanceRepository.existsById(memberId)
                    : creatorBalanceRepository.findByMemberIdAndCreatorId(memberId, creatorId).isPresent();
            if (!recorded) {
                return EarnBalance.NEW;
            }
        } catch (DataAccessException e) {
            log.error("적립 전 Redis 잔액 키를 확인하지 못했습니다. couponType={}, memberId={}, creatorId={}",
                    couponType, memberId, creatorId, e);
            return EarnBalance.NOT_READY;
        }
        return load(couponType, creatorId, memberId) ? EarnBalance.EXISTING : EarnBalance.NOT_READY;
    }

    /**
     * 잔액 키를 준비하고 적립 Lua를 실행한다(#793). {@code lua}는 requireBalance 값을 받아 Lua 결과를 돌려준다.
     * 준비 확인과 Lua 사이에 키가 사라지면 Lua가 아무것도 쓰지 않고 {@value #EARN_BALANCE_NOT_LOADED}를 돌려주므로,
     * DB 기준으로 적재한 뒤 정확히 한 번만 다시 실행한다(응모의 FR-P2-056과 같은 방식).
     *
     * @return Lua 결과. null이면 키를 준비하지 못해 Redis에 아무것도 쓰지 않은 것이므로, 호출자는 같은 requestId로
     *         다시 시도할 수 있는 실패로 끝낸다. Lua 실행 중 Redis 예외는 그대로 전파한다.
     */
    public List<?> runEarn(CouponType couponType, Long creatorId, Long memberId, Function<Boolean, List<?>> lua) {
        EarnBalance balance = prepareForEarn(couponType, creatorId, memberId);
        if (balance == EarnBalance.NOT_READY) {
            return null;
        }
        List<?> result = lua.apply(balance == EarnBalance.EXISTING);
        if (!isBalanceNotLoaded(result)) {
            return result;
        }
        if (!load(couponType, creatorId, memberId)) {
            return null;
        }
        result = lua.apply(true);
        return isBalanceNotLoaded(result) ? null : result;
    }

    private static boolean isBalanceNotLoaded(List<?> result) {
        return result != null && !result.isEmpty() && EARN_BALANCE_NOT_LOADED.equals(String.valueOf(result.get(0)));
    }

    /** 키가 존재하게 됐으면 true(이미 있었던 경우 포함). 조건 불충족이면 false. 락은 반환 전에 푼다. */
    public boolean load(CouponType couponType, Long creatorId, Long memberId) {
        boolean common = couponType == CouponType.COMMON;
        String token;
        try {
            token = common ? maintenanceLock.acquireCommon(memberId) : maintenanceLock.acquire(creatorId, memberId);
        } catch (DataAccessException e) {
            // 적재는 최선 노력이다. Redis 오류를 응모 500으로 키우지 않고 기존 BALANCE_NOT_LOADED(503)로 남긴다.
            log.error("보정 락 획득 중 오류가 발생했습니다. couponType={}, memberId={}, creatorId={}",
                    couponType, memberId, creatorId, e);
            return false;
        }
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
            // 토큰 확인과 SET NX를 원자 처리한다 - 검사 도중 락이 만료됐으면 적재하지 않고 실패로 끝낸다.
            boolean loaded = common
                    ? maintenanceLock.loadCommonBalanceIfHeld(memberId, token, balance)
                    : maintenanceLock.loadBalanceIfHeld(creatorId, memberId, token, balance);
            if (!loaded) {
                log.warn("보정 락이 검사 도중 만료돼 Redis 잔액 키를 적재하지 못했습니다. couponType={}, memberId={}, creatorId={}",
                        couponType, memberId, creatorId);
                return false;
            }
            log.warn("Redis 잔액 키를 DB 기준으로 적재했습니다. couponType={}, memberId={}, creatorId={}, balance={}",
                    couponType, memberId, creatorId, balance);
            return true;
        } catch (DataAccessException e) {
            log.error("Redis 잔액 키 적재 중 오류가 발생했습니다. couponType={}, memberId={}, creatorId={}",
                    couponType, memberId, creatorId, e);
            return false;
        } finally {
            try {
                if (common) {
                    maintenanceLock.releaseCommon(memberId, token);
                } else {
                    maintenanceLock.release(creatorId, memberId, token);
                }
            } catch (DataAccessException e) {
                // 해제 실패는 60초 lease 만료로 자연 정리된다 - 응모 요청을 실패시키지 않는다.
                log.error("보정 락 해제 중 오류가 발생했습니다. couponType={}, memberId={}, creatorId={}",
                        couponType, memberId, creatorId, e);
            }
        }
    }
}
