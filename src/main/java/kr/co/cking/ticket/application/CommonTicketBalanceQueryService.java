package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.dto.CommonTicketBalanceResponse;
import kr.co.cking.ticket.repository.UserCommonTicketBalanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link TicketBalanceQueryService}와 동일 계약의 공용 응모권 읽기 전용 조회(이슈 #219). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommonTicketBalanceQueryService {

    private final UserCommonTicketBalanceRepository userCommonTicketBalanceRepository;

    public CommonTicketBalanceResponse getBalanceDetail(Long memberId) {
        return userCommonTicketBalanceRepository.findById(memberId)
                .map(balance -> new CommonTicketBalanceResponse(memberId, balance.getBalance(), balance.getUpdatedAt()))
                .orElseGet(() -> new CommonTicketBalanceResponse(memberId, 0L, null));
    }
}
