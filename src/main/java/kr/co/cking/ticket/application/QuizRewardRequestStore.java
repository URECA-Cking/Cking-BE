package kr.co.cking.ticket.application;

import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.ticket.application.dto.QuizRewardCommand;
import kr.co.cking.ticket.domain.TicketQuizRewardRequest;
import kr.co.cking.ticket.repository.TicketEarnRequestRepository;
import kr.co.cking.ticket.repository.TicketQuizRewardRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Quiz 보상 지급 요청 접수의 DB Transaction 모음(이슈 #533). 메서드 하나가 Transaction 하나다.
 *
 * <p>접수는 전역 EARN request({@code ticket_earn_request}, reward_policy=QUIZ, PENDING)와 Quiz 요청
 * ({@code ticket_quiz_reward_request}, RECEIVED)을 한 Transaction에서 일반 INSERT로 만든다. 둘 중 하나라도 고유 제약에
 * 걸리면 예외가 나고 Transaction 전체가 롤백된다. 판정은 호출자가 새 Transaction에서 {@link #findExisting}으로 한다.
 * Mission 선점({@code TicketEarnRequestClaimService})과 달리 REQUIRES_NEW나 INSERT IGNORE를 쓰지 않는다.
 */
@Component
@RequiredArgsConstructor
public class QuizRewardRequestStore {

    private final TicketQuizRewardRequestRepository quizRewardRequestRepository;
    private final TicketEarnRequestRepository earnRequestRepository;
    private final MemberRepository memberRepository;
    private final CreatorRepository creatorRepository;
    private final Clock clock;

    /**
     * 이미 접수된 요청과 비교한 결과. 처음 보는 요청이면 빈 값이다.
     * <ul>
     *   <li>같은 rewardRequestId·같은 입력: 재요청(REPLAYED). 같은 UUID의 전역 EARN request가 QUIZ로 함께 있어야 한다</li>
     *   <li>같은 rewardRequestId·다른 입력: REQUEST_ID_CONFLICT</li>
     *   <li>Quiz 요청 없이 전역 EARN request만 있다(Mission 등 다른 원인이 쓴 UUID): REQUEST_ID_CONFLICT. 재요청으로 보지 않는다</li>
     *   <li>같은 attempt가 다른 rewardRequestId로 접수돼 있다: ATTEMPT_CONFLICT</li>
     * </ul>
     * 기존 요청은 바꾸지 않는다.
     *
     * <p>두 테이블을 여러 번 읽어 판정하므로 모두 같은 시점의 스냅샷에서 읽어야 한다. READ_COMMITTED면 Quiz 요청을 읽은
     * 뒤 다른 접수가 커밋돼 전역 EARN request만 보여, 정상 재요청을 REQUEST_ID_CONFLICT로 잘못 판정할 수 있다. 그래서
     * DB 기본 격리 수준과 관계없이 REPEATABLE_READ로 읽는다. 두 기록은 한 Transaction에서 함께 커밋되므로 같은
     * 스냅샷에서는 둘 다 보이거나 둘 다 보이지 않는다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<QuizRewardAdmission> findExisting(QuizRewardCommand command) {
        String rewardRequestId = command.rewardRequestId().toString();
        String fingerprint = command.computeFingerprint();
        Optional<TicketQuizRewardRequest> byRequestId = quizRewardRequestRepository.findByRewardRequestId(rewardRequestId);
        if (byRequestId.isPresent()) {
            TicketQuizRewardRequest request = byRequestId.get();
            if (!request.hasSamePayload(QuizRewardCommand.FINGERPRINT_VERSION, fingerprint)) {
                return Optional.of(QuizRewardAdmission.conflict(QuizRewardAdmission.Outcome.REQUEST_ID_CONFLICT));
            }
            requireGlobalClaim(rewardRequestId, fingerprint);
            return Optional.of(QuizRewardAdmission.replayed(request.getQuizRewardRequestId(), request.getStatus()));
        }
        if (earnRequestRepository.findByRequestId(rewardRequestId).isPresent()) {
            return Optional.of(QuizRewardAdmission.conflict(QuizRewardAdmission.Outcome.REQUEST_ID_CONFLICT));
        }
        if (quizRewardRequestRepository.findByAttemptId(command.attemptId()).isPresent()) {
            return Optional.of(QuizRewardAdmission.conflict(QuizRewardAdmission.Outcome.ATTEMPT_CONFLICT));
        }
        return Optional.empty();
    }

    /**
     * 회원·Creator가 있는지 확인한 뒤 전역 EARN request와 Quiz 요청을 같은 Transaction에서 만든다. 고유 제약 예외는
     * 잡지 않고 올려 Transaction 전체를 롤백한다. 실패한 이 Transaction 안에서는 기존 요청을 다시 읽지 않는다.
     *
     * @throws IllegalArgumentException 회원이나 Creator가 없다
     */
    @Transactional
    public QuizRewardAdmission insert(QuizRewardCommand command) {
        if (!memberRepository.existsById(command.memberId())) {
            throw new IllegalArgumentException("Quiz 보상 대상 회원이 없습니다. memberId=" + command.memberId());
        }
        if (!creatorRepository.existsById(command.creatorId())) {
            throw new IllegalArgumentException("Quiz 보상 Creator가 없습니다. creatorId=" + command.creatorId());
        }
        Instant now = clock.instant();
        String rewardRequestId = command.rewardRequestId().toString();
        String fingerprint = command.computeFingerprint();
        earnRequestRepository.insertPending(rewardRequestId, fingerprint, QuizRewardCommand.SOURCE, now);
        TicketQuizRewardRequest saved = quizRewardRequestRepository.saveAndFlush(new TicketQuizRewardRequest(
                rewardRequestId, command.attemptId(), command.memberId(), command.creatorId(), command.correctCount(),
                QuizRewardCommand.FINGERPRINT_VERSION, fingerprint, now));
        return QuizRewardAdmission.received(saved.getQuizRewardRequestId(), saved.getStatus());
    }

    /** Quiz 요청은 전역 EARN request와 한 Transaction에서만 만들어지므로, 없거나 다르면 데이터 정합성이 깨진 것이다. */
    private void requireGlobalClaim(String rewardRequestId, String fingerprint) {
        boolean consistent = earnRequestRepository.findByRequestId(rewardRequestId)
                .filter(claim -> claim.hasSameRequest(fingerprint, QuizRewardCommand.SOURCE))
                .isPresent();
        if (!consistent) {
            throw new IllegalStateException("Quiz 보상 요청에 맞는 전역 EARN request가 없습니다. rewardRequestId="
                    + rewardRequestId);
        }
    }
}
