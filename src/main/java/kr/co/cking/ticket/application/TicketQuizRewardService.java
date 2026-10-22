package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.dto.QuizRewardCommand;
import kr.co.cking.ticket.domain.QuizRewardRequestStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.UUID;

/**
 * Quiz 보상 Ticket 지급 요청 접수(이슈 #533). Quiz 도메인이 호출하는 Ticket 내부 계약이며 공개 API가 아니다.
 *
 * <p>접수는 Ticket 원장 반영 완료가 아니다. 호출자는 접수({@link #admit})와 지급 실행({@link QuizRewardEarnWorker#run})을
 * 각각 Transaction 밖에서 차례로 부른다. 실행은 접수 Transaction이 커밋된 뒤에만 의미가 있으며, 이미 실행 중·수락·
 * UNKNOWN·반영 완료인 요청은 Worker가 실행권을 얻지 못해 Redis를 다시 부르지 않는다. 반영 여부는 {@link #findStatus}가
 * APPLIED인지로 확인한다.
 *
 * <ol>
 *   <li>이미 접수된 요청과 비교한다. 같은 요청이면 재요청, 충돌이면 충돌 결과를 돌려주고 아무것도 만들지 않는다</li>
 *   <li>처음 보는 요청이면 전역 EARN request와 Quiz 요청을 한 Transaction에서 만든다</li>
 *   <li>동시 접수와 겹쳐 고유 제약·잠금 경쟁으로 실패하면 그 Transaction은 롤백되고, 새 Transaction에서 다시 비교해
 *       재요청·충돌로 판정한다. 그래도 기존 요청이 없으면, 잠금 경쟁(deadlock·잠금 대기 초과)이었을 때만
 *       {@link QuizRewardAdmissionRetryableException}으로 알려 같은 rewardRequestId로 다시 요청하게 한다. 그 밖의 실패는
 *       {@link IllegalStateException}이다</li>
 * </ol>
 * 롤백된 Transaction 안에서 다시 읽지 않도록 Transaction 밖에서만 호출할 수 있다.
 */
@Service
@RequiredArgsConstructor
public class TicketQuizRewardService {

    private final QuizRewardRequestStore store;

    /**
     * rewardRequestId로 지급 요청 상태를 조회한다. APPLIED일 때만 Ticket 원장·잔액에 반영된 것이다. 접수되지 않았으면 빈
     * 값이다. 실행권·fingerprint 같은 내부 실행 정보는 돌려주지 않는다.
     */
    public Optional<QuizRewardRequestStatus> findStatus(UUID rewardRequestId) {
        return store.findStatus(rewardRequestId.toString());
    }

    public QuizRewardAdmission admit(QuizRewardCommand command) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Quiz 보상 접수는 Transaction 밖에서 호출해야 합니다.");
        }
        Optional<QuizRewardAdmission> existing = store.findExisting(command);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return store.insert(command);
        } catch (ConcurrencyFailureException exception) {
            return store.findExisting(command)
                    .orElseThrow(() -> new QuizRewardAdmissionRetryableException(
                            "잠금 경쟁으로 Quiz 보상 요청을 접수하지 못했습니다. 같은 요청으로 다시 시도합니다. rewardRequestId="
                                    + command.rewardRequestId(), exception));
        } catch (DataIntegrityViolationException exception) {
            return store.findExisting(command)
                    .orElseThrow(() -> new IllegalStateException("Quiz 보상 요청을 접수하지 못했습니다. rewardRequestId="
                            + command.rewardRequestId(), exception));
        }
    }
}
