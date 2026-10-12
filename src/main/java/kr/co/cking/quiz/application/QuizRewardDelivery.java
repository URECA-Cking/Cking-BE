package kr.co.cking.quiz.application;

import kr.co.cking.quiz.application.dto.QuizAttemptResult;
import kr.co.cking.quiz.application.dto.QuizRewardPaymentStatus;
import kr.co.cking.quiz.domain.QuizAttempt;
import kr.co.cking.quiz.repository.QuizAttemptRepository;
import kr.co.cking.ticket.application.QuizRewardAdmission;
import kr.co.cking.ticket.application.QuizRewardEarnWorker;
import kr.co.cking.ticket.application.TicketQuizRewardService;
import kr.co.cking.ticket.application.dto.QuizRewardCommand;
import kr.co.cking.ticket.domain.QuizRewardRequestStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 저장·검증된 Fan Quiz 응시 결과를 Ticket Quiz 보상 엔진에 전달하고, 응답 시점의 지급 상태를 알려준다(이슈 #543).
 *
 * <p>입력은 저장된 응시의 attemptId·memberId·creatorId·correctCount·rewardRequestId뿐이며 새 UUID를 만들지 않는다.
 * 정답이 0개면 아무것도 하지 않는다. 접수({@code admit})는 언제나 같은 요청으로 멱등하고, 실행({@code run})은 접수된 요청이
 * RECEIVED·RETRYABLE_FAILURE일 때만 부른다. 실제 실행 여부는 Ticket 엔진의 실행권 규칙이 정하므로 EXECUTING·ACCEPTED·
 * UNKNOWN·APPLIED는 다시 실행되지 않는다. 전달 실패는 기록만 하고 응시 결과에는 영향을 주지 않는다. Transaction 밖에서만
 * 호출한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuizRewardDelivery {

    private final TicketQuizRewardService rewardService;
    private final QuizRewardEarnWorker rewardWorker;
    private final QuizAttemptRepository attempts;
    private final QuizAttemptSnapshotReader snapshotReader;

    /** 검증된 응시 결과를 전달한다. 예외를 올리지 않는다. */
    public void deliver(QuizAttemptResult result, Long memberId) {
        if (result.correctCount() <= 0 || result.reward().requestId() == null) {
            return;
        }
        QuizRewardCommand command = new QuizRewardCommand(result.attemptId(), memberId, result.creatorId(),
                result.correctCount(), result.reward().requestId());
        try {
            QuizRewardAdmission admission = rewardService.admit(command);
            if (admission.outcome() != QuizRewardAdmission.Outcome.RECEIVED
                    && admission.outcome() != QuizRewardAdmission.Outcome.REPLAYED) {
                log.error("Quiz 보상 접수가 충돌했습니다. attemptId={}, outcome={}", result.attemptId(),
                        admission.outcome());
                return;
            }
            if (admission.status() == QuizRewardRequestStatus.RECEIVED
                    || admission.status() == QuizRewardRequestStatus.RETRYABLE_FAILURE) {
                rewardWorker.run(command.rewardRequestId());
            }
        } catch (RuntimeException e) {
            // 응시는 이미 커밋됐다. 누락분은 재전달 배치가 다시 시도한다.
            log.warn("Quiz 보상 전달에 실패했습니다. attemptId={}", result.attemptId(), e);
        }
    }

    /** 응답 시점의 지급 상태. 조회 실패는 미접수로 보지 않고 UNAVAILABLE이다. */
    public QuizRewardPaymentStatus paymentStatusOf(QuizAttemptResult result) {
        if (result.correctCount() <= 0 || result.reward().requestId() == null) {
            return QuizRewardPaymentStatus.NOT_ELIGIBLE;
        }
        Optional<QuizRewardRequestStatus> status;
        try {
            status = rewardService.findStatus(result.reward().requestId());
        } catch (RuntimeException e) {
            log.warn("Quiz 보상 지급 상태를 조회하지 못했습니다. attemptId={}", result.attemptId(), e);
            return QuizRewardPaymentStatus.UNAVAILABLE;
        }
        if (status.isEmpty()) {
            return QuizRewardPaymentStatus.PENDING;
        }
        return switch (status.get()) {
            case APPLIED -> QuizRewardPaymentStatus.PAID;
            case UNKNOWN -> QuizRewardPaymentStatus.DELAYED;
            case RECEIVED, EXECUTING, ACCEPTED, RETRYABLE_FAILURE -> QuizRewardPaymentStatus.PENDING;
        };
    }

    /**
     * attemptId가 {@code afterAttemptId}보다 큰 양수 점수 응시를 PK 순서로 최대 {@code limit}건 다시 전달한다. 스냅샷 검증에
     * 실패한 응시는 건너뛴다. 다음 배치의 기준 attemptId를 돌려주며, 끝까지 읽었으면 0이다.
     */
    public long redeliverAfter(long afterAttemptId, int limit) {
        List<QuizAttempt> page = attempts.findByAttemptIdGreaterThanAndCorrectCountGreaterThanOrderByAttemptIdAsc(
                afterAttemptId, 0, PageRequest.of(0, limit));
        for (QuizAttempt attempt : page) {
            QuizAttemptResult result;
            try {
                result = snapshotReader.read(attempt);
            } catch (RuntimeException e) {
                log.error("스냅샷 검증에 실패한 응시는 Quiz 보상을 전달하지 않습니다. attemptId={}",
                        attempt.getAttemptId(), e);
                continue;
            }
            deliver(result, attempt.getMemberId());
        }
        return page.size() < limit ? 0L : page.get(page.size() - 1).getAttemptId();
    }
}
