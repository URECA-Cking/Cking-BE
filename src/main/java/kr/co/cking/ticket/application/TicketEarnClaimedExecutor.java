package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.dto.EarnCommand;
import kr.co.cking.ticket.application.dto.EarnLookupResult;
import kr.co.cking.ticket.application.dto.EarnResult;

/** 전역 EARN request claim을 이미 선점한 내부 경로만 Redis EARN을 실행한다. */
interface TicketEarnClaimedExecutor {

    EarnResult earnClaimed(EarnCommand command);

    EarnLookupResult findExistingClaimed(EarnCommand command);
}
