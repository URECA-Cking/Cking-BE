package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.dto.QuizEarnEvent;
import kr.co.cking.ticket.domain.QuizRewardRequestStatus;
import kr.co.cking.ticket.domain.TicketQuizRewardRequest;

import java.util.Set;

/**
 * 테스트 전용 실행권 정책. 실행권은 확인하지 않고 EXECUTING·ACCEPTED·UNKNOWN 상태면 반영을 허용해, 원장 반영 동작만
 * 검증할 때 쓴다. 운영 소스에는 두지 않는다. 운영 기본값은 {@link FailClosedQuizRewardExecutionPolicy}다.
 */
public class StatusOnlyTestQuizRewardExecutionPolicy implements QuizRewardExecutionPolicy {

    private static final Set<QuizRewardRequestStatus> APPLICABLE = Set.of(QuizRewardRequestStatus.EXECUTING,
            QuizRewardRequestStatus.ACCEPTED, QuizRewardRequestStatus.UNKNOWN);

    @Override
    public void requireApplicable(TicketQuizRewardRequest request, QuizEarnEvent event) {
        if (!APPLICABLE.contains(request.getStatus())) {
            throw new IllegalStateException("테스트 정책에서 반영할 수 없는 상태입니다. status=" + request.getStatus());
        }
    }
}
