package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.dto.CommonEarnCommand;
import kr.co.cking.ticket.application.dto.EarnLookupResult;
import kr.co.cking.ticket.application.dto.EarnResult;

/**
 * 공용 응모권(크리에이터 무관) EARN 진입점(이슈 #219). {@link TicketEarnService}와
 * 동일한 계약(멱등성·중복 적립 가드·Redis Balance 증가·Stream 발행을 원자 처리)을
 * 크리에이터 축 없이 제공한다.
 */
public interface CommonTicketEarnService {

    EarnResult earn(CommonEarnCommand command);

    EarnLookupResult findExisting(CommonEarnCommand command);
}
