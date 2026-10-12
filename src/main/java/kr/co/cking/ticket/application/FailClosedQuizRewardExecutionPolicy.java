package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.dto.QuizEarnEvent;
import kr.co.cking.ticket.domain.TicketQuizRewardRequest;
import org.springframework.stereotype.Component;

/**
 * 실행권 정책이 정해지기 전의 운영 기본 구현(이슈 #533). 새 원장 반영을 항상 막는다.
 *
 * <p>{@code executionToken}이 이 요청에 발급한 실행권인지 확인할 저장소가 아직 없다. 요청 상태와 fingerprint는 공개된
 * 입력으로 만들 수 있어 실행 증명이 되지 않으므로, 확인할 수 없는 메시지는 반영하지 않는다(fail-closed). 이미 반영된
 * 요청의 중복 전달은 이 정책을 거치지 않으므로 그대로 멱등 처리된다. 실행권 저장·검증이 생기면 그 정책으로 바꾼다.
 */
@Component
public class FailClosedQuizRewardExecutionPolicy implements QuizRewardExecutionPolicy {

    @Override
    public void requireApplicable(TicketQuizRewardRequest request, QuizEarnEvent event) {
        throw new IllegalStateException("실행권을 확인할 수 없어 Quiz 보상 EARN을 반영하지 않습니다. status="
                + request.getStatus() + ", requestId=" + event.requestId());
    }
}
