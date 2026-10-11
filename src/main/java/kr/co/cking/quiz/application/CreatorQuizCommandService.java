package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.quiz.application.dto.QuizDecisionView;
import kr.co.cking.quiz.application.dto.QuizPublicationView;
import kr.co.cking.quiz.domain.Quiz;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizRevision;
import kr.co.cking.quiz.domain.QuizRevisionDecision;
import kr.co.cking.quiz.repository.QuizRepository;
import kr.co.cking.quiz.repository.QuizRevisionDecisionRepository;
import kr.co.cking.quiz.repository.QuizRevisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/**
 * Creator 본인 Quiz의 revision 승인·거절과 게시·게시 취소(이슈 #521).
 *
 * <p>모든 명령은 Quiz 행을 쓰기 잠금으로 읽어 같은 Quiz의 명령을 직렬화한다. 잠금 순서는 Quiz → revision →
 * 결정이다. 승인과 게시는 저장 내용을 revision 해시로 검증한 뒤 {@link QuizApprovalPolicy}를 통과해야 하며,
 * 운영 구현은 Judge 연동 전까지 항상 거부한다. 거절과 게시 취소는 이 정책을 거치지 않는다.
 *
 * <p>현재 상태가 이미 요청 결과와 같으면 기존 상태를 그대로 반환한다(같은 결정 재요청, 같은 revision 재게시,
 * 게시 중이 아닌 Quiz의 게시 취소). 이미 다른 결정이 있는 revision은 {@code QUIZ_REVISION_ALREADY_DECIDED}다.
 *
 * <p>READ COMMITTED를 쓴다. REPEATABLE READ에서는 잠금 전 Member 조회 시점의 snapshot을 계속 읽으므로, 잠금을
 * 기다린 요청이 앞 요청이 커밋한 결정·revision을 보지 못하고 중복 INSERT로 실패할 수 있다.
 */
@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class CreatorQuizCommandService {

    private final QuizCreatorLookup creatorLookup;
    private final QuizRepository quizRepository;
    private final QuizRevisionRepository revisionRepository;
    private final QuizRevisionDecisionRepository decisionRepository;
    private final QuizRevisionReader revisionReader;
    private final QuizApprovalPolicy approvalPolicy;
    private final Clock clock;

    public QuizDecisionView approve(Long memberId, Long quizId, Long revisionId) {
        Quiz quiz = requireOwnedQuizForUpdate(memberId, quizId);
        QuizRevision revision = requireRevision(quiz, revisionId);
        Optional<QuizRevisionDecision> existing = decisionRepository.findByRevisionId(revisionId);
        if (existing.isPresent()) {
            return replayOrConflict(quiz, existing.get(), true);
        }
        revisionReader.readVerified(quiz, revision);
        QuizApprovalPolicy.Basis basis = approvalPolicy.requireApprovable(target(quiz, revision));
        QuizRevisionDecision decision = decisionRepository.save(QuizRevisionDecision.approve(
                revisionId, memberId, basis.judgeEvaluationId(), basis.judgeEvaluationSha256(), clock.instant()));
        return QuizDecisionView.of(quizId, decision);
    }

    /** @param reason 선택 사유. 비어 있으면 저장하지 않는다 */
    public QuizDecisionView reject(Long memberId, Long quizId, Long revisionId, String reason) {
        Quiz quiz = requireOwnedQuizForUpdate(memberId, quizId);
        requireRevision(quiz, revisionId);
        Optional<QuizRevisionDecision> existing = decisionRepository.findByRevisionId(revisionId);
        if (existing.isPresent()) {
            return replayOrConflict(quiz, existing.get(), false);
        }
        String normalizedReason = reason == null || reason.isBlank() ? null : reason;
        if (normalizedReason != null && normalizedReason.length() > QuizRevisionDecision.MAX_REASON_LENGTH) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        QuizRevisionDecision decision = decisionRepository.save(
                QuizRevisionDecision.reject(revisionId, memberId, normalizedReason, clock.instant()));
        return QuizDecisionView.of(quizId, decision);
    }

    /**
     * 최신 revision이 승인돼 있고, 저장 내용이 revision 해시와 같고, 승인 근거가 여전히 유효할 때 게시한다.
     * 다른 revision을 게시 중이면 게시 revision을 바꾼다.
     */
    public QuizPublicationView publish(Long memberId, Long quizId, Long revisionId) {
        Quiz quiz = requireOwnedQuizForUpdate(memberId, quizId);
        QuizRevision revision = requireRevision(quiz, revisionId);
        if (quiz.isPublishing(revisionId)) {
            return QuizPublicationView.of(quiz);
        }
        QuizRevision latest = revisionRepository.findTopByQuizIdOrderByRevisionNoDesc(quizId)
                .orElseThrow(() -> new IllegalStateException("revision이 없는 Quiz입니다. quizId=" + quizId));
        if (!latest.getRevisionId().equals(revisionId)) {
            throw new BusinessException(QuizErrorCode.QUIZ_REVISION_NOT_LATEST);
        }
        QuizRevisionDecision approval = decisionRepository.findByRevisionId(revisionId)
                .filter(QuizRevisionDecision::isApproved)
                .orElseThrow(() -> new BusinessException(QuizErrorCode.QUIZ_REVISION_NOT_APPROVED));
        revisionReader.readVerified(quiz, revision);
        approvalPolicy.requireStillApproved(target(quiz, revision), approval);
        quiz.publish(revisionId, clock.instant());
        return QuizPublicationView.of(quiz);
    }

    /** Judge 상태와 무관하게 게시를 취소한다. revision과 결정 기록은 그대로 둔다. */
    public QuizPublicationView unpublish(Long memberId, Long quizId) {
        Quiz quiz = requireOwnedQuizForUpdate(memberId, quizId);
        if (quiz.isPublished()) {
            quiz.unpublish(clock.instant());
        }
        return QuizPublicationView.of(quiz);
    }

    /** 없는 Quiz는 404, 다른 Creator의 Quiz는 403 — 존재 여부와 소유권을 구분한다. */
    private Quiz requireOwnedQuizForUpdate(Long memberId, Long quizId) {
        Creator creator = creatorLookup.requireCreator(memberId);
        Quiz quiz = quizRepository.findByIdForUpdate(quizId)
                .orElseThrow(() -> new BusinessException(QuizErrorCode.QUIZ_NOT_FOUND));
        if (!quiz.isOwnedBy(creator.getCreatorId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return quiz;
    }

    private QuizRevision requireRevision(Quiz quiz, Long revisionId) {
        return revisionRepository.findById(revisionId)
                .filter(revision -> revision.getQuizId().equals(quiz.getQuizId()))
                .orElseThrow(() -> new BusinessException(QuizErrorCode.QUIZ_REVISION_NOT_FOUND));
    }

    private QuizDecisionView replayOrConflict(Quiz quiz, QuizRevisionDecision existing, boolean approving) {
        if (existing.isApproved() != approving) {
            throw new BusinessException(QuizErrorCode.QUIZ_REVISION_ALREADY_DECIDED);
        }
        return QuizDecisionView.of(quiz.getQuizId(), existing);
    }

    private QuizApprovalPolicy.Target target(Quiz quiz, QuizRevision revision) {
        return new QuizApprovalPolicy.Target(quiz.getQuizId(), revision.getRevisionId(),
                quiz.getContentTextSha256(), revision.getRevisionSha256());
    }
}
