package kr.co.cking.ticket.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.co.cking.ticket.domain.UserTicketBalance;
import kr.co.cking.ticket.repository.UserTicketBalanceRepository;

/**
 * 다른 도메인(event 등)이 잔액을 읽기 전용으로 조회할 때 쓰는 내부 진입점.
 * 잔액 적립·차감(Ledger 처리)은 T1-04 담당 범위라 여기서 다루지 않는다 —
 * 이 클래스가 커지면 그 작업과 조율해서 합친다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TicketBalanceQueryService {

    private final UserTicketBalanceRepository userTicketBalanceRepository;

    public long getBalance(Long creatorId, Long memberId) {
        return userTicketBalanceRepository.findByMemberIdAndCreatorId(memberId, creatorId)
                .map(UserTicketBalance::getBalance)
                .orElse(0L);
    }
}
