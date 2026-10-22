package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.dto.EarnCommand;
import kr.co.cking.ticket.application.dto.EarnLookupResult;
import kr.co.cking.ticket.application.dto.EarnResult;

/** durable DB request를 선행해 평생 1회 보상을 안전하게 Redis EARN으로 전달한다. */
public interface TicketOnceEarnService {

    EarnResult earn(EarnCommand command);

    EarnLookupResult findExisting(EarnCommand command);
}
