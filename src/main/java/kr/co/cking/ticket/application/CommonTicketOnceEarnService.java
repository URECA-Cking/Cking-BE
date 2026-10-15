package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.dto.CommonOnceEarnCommand;
import kr.co.cking.ticket.application.dto.EarnLookupResult;
import kr.co.cking.ticket.application.dto.EarnResult;

import java.util.Set;

/** 웰컴 판정과 무관하게 회원·보상 종류별 공용 응모권을 평생 한 번 적립한다. */
public interface CommonTicketOnceEarnService {
    /** 영속 Business Key를 먼저 선점하고 공용 Redis EARN을 실행한다. */
    EarnResult earn(CommonOnceEarnCommand command);

    /** 같은 requestId의 승인 결과와 검증된 영속 선점 여부를 읽기 전용으로 확인한다. */
    EarnLookupResult findExisting(CommonOnceEarnCommand command);

    /** 승인된 보상 종류만 읽어 상태 조회에 제공한다. */
    Set<String> findAcceptedRewardTypes(Long memberId);
}
