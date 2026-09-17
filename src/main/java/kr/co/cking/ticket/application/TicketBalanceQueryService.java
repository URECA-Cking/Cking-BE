package kr.co.cking.ticket.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.co.cking.ticket.application.dto.TicketBalanceResponse;
import kr.co.cking.ticket.domain.UserTicketBalance;
import kr.co.cking.ticket.repository.UserTicketBalanceRepository;

/**
 * 응모권 잔액을 읽기 전용으로 조회하는 공통 내부 진입점.
 * Event 상세 조회와 Creator Ticket 조회에서 재사용한다.
 * 잔액 적립·차감 및 Ledger 기록은 별도 응모권 처리 흐름에서 담당한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TicketBalanceQueryService {

    private final UserTicketBalanceRepository userTicketBalanceRepository;

    public TicketBalanceResponse getBalanceDetail(Long creatorId, Long memberId) {
        return userTicketBalanceRepository.findByMemberIdAndCreatorId(memberId, creatorId)
                .map(balance -> new TicketBalanceResponse(
                        memberId,
                        creatorId,
                        balance.getBalance(),
                        balance.getUpdatedAt()
                ))
                .orElseGet(() -> new TicketBalanceResponse(memberId, creatorId, 0L, null));
    }

    public long getBalance(Long creatorId, Long memberId) {
        return getBalanceDetail(creatorId, memberId).balance();
    }
}
