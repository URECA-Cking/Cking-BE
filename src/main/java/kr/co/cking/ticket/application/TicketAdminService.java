package kr.co.cking.ticket.application;

import org.springframework.stereotype.Service;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.ticket.application.dto.TicketBalanceResponse;
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

    public TicketBalanceResponse resync(Long adminId, Long memberId, Long creatorId, String reason) {
        memberQueryService.validateAdmin(adminId);
        if (userTicketBalanceRepository.findByMemberIdAndCreatorId(memberId, creatorId).isEmpty()) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "보정 대상 Balance가 없습니다.");
        }
        ticketCompensationService.resyncRedisToDb(memberId, creatorId, reason);
        return ticketBalanceQueryService.getBalanceDetail(creatorId, memberId);
    }
}
