package kr.co.cking.ticket.application;

import java.time.Instant;
import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.ticket.application.dto.AdminTicketLedgerPage;
import kr.co.cking.ticket.application.config.CommonTicketRedisKeys;
import kr.co.cking.ticket.application.config.TicketRedisKeys;
import kr.co.cking.ticket.application.dto.AdminTicketBalanceResponse;
import kr.co.cking.ticket.application.dto.CommonTicketBalanceResponse;
import kr.co.cking.ticket.application.dto.TicketBalanceResponse;
import kr.co.cking.ticket.domain.CouponType;
import kr.co.cking.ticket.domain.TicketErrorCode;
import kr.co.cking.ticket.domain.UserCommonTicketBalance;
import kr.co.cking.ticket.domain.UserTicketBalance;
import kr.co.cking.ticket.repository.UserCommonTicketBalanceRepository;
import kr.co.cking.ticket.repository.UserTicketBalanceRepository;

/**
 * 관리자 응모권 잔액 조회와 수동 보정의 업무 권한 및 저장소 접근을 담당한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TicketAdminService {

    private final MemberQueryService memberQueryService;
    private final UserTicketBalanceRepository userTicketBalanceRepository;
    private final TicketCompensationService ticketCompensationService;
    private final TicketBalanceQueryService ticketBalanceQueryService;
    private final UserCommonTicketBalanceRepository userCommonTicketBalanceRepository;
    private final CommonTicketCompensationService commonTicketCompensationService;
    private final CommonTicketBalanceQueryService commonTicketBalanceQueryService;
    private final TicketQueryService ticketQueryService;
    private final CommonTicketQueryService commonTicketQueryService;
    private final StringRedisTemplate redisTemplate;

    /** 관리자가 지정한 회원의 Creator 전용 또는 공용 응모권 이력을 조회한다. */
    public AdminTicketLedgerPage getHistory(Long adminId, Long memberId, CouponType couponType,
                                            Long creatorId, int size, String cursor) {
        memberQueryService.validateAdmin(adminId);
        return switch (couponType) {
            case CREATOR -> AdminTicketLedgerPage.from(ticketQueryService.getLedger(
                    requireCreatorId(creatorId), memberId, size, cursor));
            case COMMON -> {
                if (creatorId != null) {
                    throw new BusinessException(CommonErrorCode.VALIDATION_FAILED,
                            "creatorId: COMMON 응모권 조회에는 사용할 수 없습니다.");
                }
                yield AdminTicketLedgerPage.from(commonTicketQueryService.getLedger(memberId, size, cursor));
            }
        };
    }

    /** Creator 전용 이력 조회에 필요한 creatorId가 전달됐는지 검증한다. */
    private Long requireCreatorId(Long creatorId) {
        if (creatorId == null || creatorId <= 0) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED, "creatorId: CREATOR 응모권 조회에는 creatorId가 필요합니다.");
        }
        return creatorId;
    }

    /** 관리자가 지정한 Creator 전용 응모권 잔액을 DB 기준으로 재동기화한다. */
    public TicketBalanceResponse resync(Long adminId, Long memberId, Long creatorId, String reason) {
        memberQueryService.validateAdmin(adminId);
        if (userTicketBalanceRepository.findByMemberIdAndCreatorId(memberId, creatorId).isEmpty()) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "보정 대상 Balance가 없습니다.");
        }
        ticketCompensationService.resyncRedisToDb(memberId, creatorId, reason);
        return ticketBalanceQueryService.getBalanceDetail(creatorId, memberId);
    }

    /** 관리자가 지정한 공용 응모권 잔액을 DB 기준으로 재동기화한다. */
    public CommonTicketBalanceResponse resyncCommon(Long adminId, Long memberId, String reason) {
        memberQueryService.validateAdmin(adminId);
        if (!userCommonTicketBalanceRepository.existsById(memberId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "보정 대상 공용 Balance가 없습니다.");
        }
        commonTicketCompensationService.resyncRedisToDb(memberId, reason);
        return commonTicketBalanceQueryService.getBalanceDetail(memberId);
    }

    /** 관리자 권한과 응모권 종류별 입력을 검증하고 두 저장소의 잔액 관측값을 반환한다. */
    public AdminTicketBalanceResponse getBalance(Long adminId, Long memberId, CouponType couponType, Long creatorId) {
        memberQueryService.validateAdmin(adminId);
        if ((couponType == CouponType.CREATOR && creatorId == null)
                || (couponType == CouponType.COMMON && creatorId != null)) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED,
                    "CREATOR 조회에는 creatorId가 필요하고 COMMON 조회에는 사용할 수 없습니다.");
        }

        Optional<Long> mysqlBalance = couponType == CouponType.CREATOR
                ? userTicketBalanceRepository.findByMemberIdAndCreatorId(memberId, creatorId)
                        .map(UserTicketBalance::getBalance)
                : userCommonTicketBalanceRepository.findById(memberId)
                        .map(UserCommonTicketBalance::getBalance);
        String redisKey = couponType == CouponType.CREATOR
                ? TicketRedisKeys.balance(creatorId, memberId)
                : CommonTicketRedisKeys.balance(memberId);
        Optional<Long> redisBalance = readRedisBalance(redisKey);
        Long difference = redisBalance.isPresent() && mysqlBalance.isPresent()
                ? redisBalance.get() - mysqlBalance.get() : null;

        return new AdminTicketBalanceResponse(memberId, creatorId, couponType,
                redisBalance.orElse(null), mysqlBalance.orElse(null), redisBalance.isPresent(),
                mysqlBalance.isPresent(), difference, Instant.now());
    }

    /** Redis 키 미존재는 빈 값으로, 통신·값 오류는 조회 실패로 분리한다. */
    private Optional<Long> readRedisBalance(String redisKey) {
        try {
            String value = redisTemplate.opsForValue().get(redisKey);
            return value == null ? Optional.empty() : Optional.of(Long.parseLong(value));
        } catch (DataAccessException | NumberFormatException e) {
            log.warn("Redis 응모권 잔액 조회에 실패했습니다. redisKey={}", redisKey, e);
            throw new BusinessException(TicketErrorCode.REDIS_BALANCE_QUERY_FAILED);
        }
    }
}
