package kr.co.cking.quiz.scheduler;

import kr.co.cking.quiz.application.QuizRewardDelivery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 저장된 양수 점수 응시를 PK 순서로 조금씩 다시 전달해 Ticket 보상 누락을 메운다(이슈 #543). 커밋 직후 전달이 실패했거나,
 * 서버가 중단됐거나, Worker가 꺼져 있어 RECEIVED로 남은 요청이 대상이다. 전달은 Ticket 엔진의 멱등·실행권 규칙을 그대로
 * 따르므로 이미 실행 중·수락·UNKNOWN·반영 완료인 요청은 다시 실행되지 않는다.
 *
 * <p>커서는 메모리에만 둔다. 재시작하면 처음부터 다시 훑으며, 끝까지 읽으면 처음으로 돌아간다. Quiz 보상 Worker와 같은
 * 설정({@code cking.ticket.quiz-reward.worker-enabled}, 기본 false)이 켜졌을 때만 동작한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "cking.ticket.quiz-reward.worker-enabled", havingValue = "true")
public class QuizRewardDeliveryScheduler {

    private final QuizRewardDelivery rewardDelivery;
    private final int batchSize;
    private long cursor;

    public QuizRewardDeliveryScheduler(
            QuizRewardDelivery rewardDelivery,
            @Value("${cking.quiz.reward-delivery.batch-size:100}") int batchSize
    ) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Quiz 보상 재전달 배치 크기는 양수여야 합니다: " + batchSize);
        }
        this.rewardDelivery = rewardDelivery;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${cking.quiz.reward-delivery.interval-ms:60000}",
            initialDelayString = "${cking.quiz.reward-delivery.interval-ms:60000}")
    public void redeliver() {
        try {
            cursor = rewardDelivery.redeliverAfter(cursor, batchSize);
        } catch (RuntimeException e) {
            // 후보 조회 실패는 이번 주기만 건너뛴다. 커서는 그대로 두고 다음 주기에 같은 위치부터 다시 읽는다.
            log.warn("Quiz 보상 재전달 후보를 읽지 못했습니다. cursor={}", cursor, e);
        }
    }

    long cursor() {
        return cursor;
    }
}
