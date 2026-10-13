package kr.co.cking.ticket.application;

import org.springframework.stereotype.Service;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.ticket.application.dto.AdminTicketLedgerPage;
import kr.co.cking.ticket.application.dto.CommonTicketBalanceResponse;
import kr.co.cking.ticket.application.dto.TicketBalanceResponse;
import kr.co.cking.ticket.domain.CouponType;
import kr.co.cking.ticket.repository.UserCommonTicketBalanceRepository;
import kr.co.cking.ticket.repository.UserTicketBalanceRepository;
import lombok.RequiredArgsConstructor;

/**
 * #207: {@link TicketCompensationService#resyncRedisToDb}는 구현돼 있었지만 호출 진입점이
 * 없었다(#178 Dead Stream 관리자 API와 같은 상황). 운영자가 정합성 배치의 지속 불일치
 * 경고나 Redis 잔액 키 유실 경고를 확인한 뒤 호출하는 관리자 API의 서비스 계층이다.
 */
@Service
@RequiredArgsConstructor
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

    /** 관리자가 지정한 회원의 Creator 전용 또는 공용 응모권 이력을 조회한다. */
    public AdminTicketLedgerPage getHistory(Long adminId, Long memberId, CouponType couponType,
                                            Long creatorId, int size, String cursor) {
        memberQueryService.validateAdmin(adminId);
        return switch (couponType) {
            case CREATOR -> AdminTicketLedgerPage.from(ticketQueryService.getLedger(
                    requireCreatorId(creatorId), memberId, size, cursor));
            case COMMON -> AdminTicketLedgerPage.from(commonTicketQueryService.getLedger(memberId, size, cursor));
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
}
