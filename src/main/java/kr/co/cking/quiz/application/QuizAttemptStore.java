package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.quiz.application.dto.QuizAttemptCommand;
import kr.co.cking.quiz.application.dto.QuizAttemptResult;
import kr.co.cking.quiz.domain.Quiz;
import kr.co.cking.quiz.domain.QuizAttempt;
import kr.co.cking.quiz.domain.QuizAttemptAnswer;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.repository.QuizRepository;
import kr.co.cking.quiz.repository.QuizRevisionRepository;
import kr.co.cking.quiz.repository.QuizAttemptRepository;
import kr.co.cking.quiz.repository.QuizAttemptAnswerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

/** Quiz 행 잠금과 검증·채점·스냅샷 저장을 묶는 짧은 트랜잭션. 외부 시스템을 호출하지 않는다. */
@Component @RequiredArgsConstructor
public class QuizAttemptStore {
    private final MemberRepository members;
    private final CreatorRepository creators;
    private final QuizRepository quizzes;
    private final QuizRevisionRepository revisions;
    private final QuizRevisionReader revisionReader;
    private final QuizAttemptRepository attempts;
    private final QuizAttemptAnswerRepository answers;
    private final QuizAttemptSnapshotReader snapshotReader;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<QuizAttemptResult> findExisting(QuizAttemptCommand command) {
        requireMember(command.memberId());
        return existing(command);
    }

    @Transactional(readOnly = true)
    public QuizAttemptResult findMine(Long memberId, Long creatorId, Long quizId) {
        requireMember(memberId);
        return attempts.findByMemberIdAndQuizId(memberId, quizId)
                .filter(a -> a.getCreatorId().equals(creatorId)).map(snapshotReader::read)
                .orElseThrow(() -> new BusinessException(QuizErrorCode.QUIZ_ATTEMPT_NOT_FOUND));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public QuizAttemptResult submit(QuizAttemptCommand command) {
        requireMember(command.memberId());
        Quiz quiz = quizzes.findByIdForUpdate(command.quizId())
                .filter(q -> q.isOwnedBy(command.creatorId()))
                .orElseThrow(() -> new BusinessException(QuizErrorCode.QUIZ_NOT_FOUND));
        // 잠금 대기 동안 선행 제출이 커밋했을 수 있다. 게시 상태보다 replay를 먼저 판정한다.
        var replay = existing(command);
        if (replay.isPresent()) return replay.get();
        var creator = creators.findById(quiz.getCreatorId())
                .orElseThrow(() -> new BusinessException(QuizErrorCode.QUIZ_NOT_FOUND));
        if (creator.getMemberId().equals(command.memberId())) throw new BusinessException(CommonErrorCode.FORBIDDEN);
        quiz.requireNotClosed();
        if (!quiz.isPublished()) throw new BusinessException(QuizErrorCode.QUIZ_NOT_FOUND);
        if (!quiz.getPublishedRevisionId().equals(command.revisionId()))
            throw new BusinessException(QuizErrorCode.QUIZ_PUBLISHED_REVISION_CHANGED);
        var revision = revisions.findById(command.revisionId()).filter(r -> r.getQuizId().equals(quiz.getQuizId()))
                .orElseThrow(() -> new BusinessException(QuizErrorCode.QUIZ_REVISION_INTEGRITY_FAILED));
        var contents = revisionReader.readVerified(quiz, revision);
        if (command.answers().size() != contents.size()) throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        var questions = new ArrayList<QuizAttemptResult.Question>();
        for (int i = 0; i < contents.size(); i++) {
            var q = contents.get(i);
            int selected = command.answers().get(i).selectedOptionIndex();
            if (q.correctOptionIndex() < 0 || q.correctOptionIndex() >= q.options().size())
                throw new BusinessException(QuizErrorCode.QUIZ_REVISION_INTEGRITY_FAILED);
            if (selected >= q.options().size()) throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
            questions.add(new QuizAttemptResult.Question(i, q.question(), q.options(), selected,
                    q.correctOptionIndex(), selected == q.correctOptionIndex(), q.explanation(), q.sourceEvidence()));
        }
        int correctCount = (int) questions.stream().filter(QuizAttemptResult.Question::correct).count();
        UUID rewardId = correctCount > 0 ? UUID.randomUUID() : null;
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var result = new QuizAttemptResult(null, command.requestId(), command.creatorId(), command.quizId(),
                command.revisionId(), revision.getTitle(), quiz.getSourceVideoId(), contents.size(), correctCount,
                now, revision.getRevisionSha256(), null, questions,
                new QuizAttemptResult.Reward(correctCount, rewardId, correctCount > 0 ? "NOT_INTEGRATED" : "NOT_ELIGIBLE"));
        String fingerprint = QuizAttemptHashes.request(command);
        String snapshotHash = QuizAttemptHashes.snapshot(command.memberId(), fingerprint, result);
        var saved = attempts.saveAndFlush(new QuizAttempt(command.memberId(), command.creatorId(), command.quizId(),
                command.revisionId(), command.requestId().toString(), fingerprint, revision.getRevisionSha256(),
                snapshotHash, revision.getTitle(), quiz.getSourceVideoId(), contents.size(), correctCount,
                rewardId == null ? null : rewardId.toString(), now));
        answers.saveAllAndFlush(questions.stream().map(q -> new QuizAttemptAnswer(saved.getAttemptId(), q.questionNo(),
                q.question(), objectMapper.writeValueAsString(q.options()), q.options().size(), q.selectedOptionIndex(),
                q.correctOptionIndex(), q.explanation(), q.sourceEvidence())).toList());
        return snapshotReader.read(saved);
    }

    private Optional<QuizAttemptResult> existing(QuizAttemptCommand command) {
        var byRequest = attempts.findByMemberIdAndRequestId(command.memberId(), command.requestId().toString());
        if (byRequest.isPresent()) {
            var a = byRequest.get();
            if (!a.getRequestFingerprint().equals(QuizAttemptHashes.request(command)))
                throw new BusinessException(QuizErrorCode.QUIZ_ATTEMPT_REQUEST_CONFLICT);
            return Optional.of(snapshotReader.read(a));
        }
        if (attempts.findByMemberIdAndQuizId(command.memberId(), command.quizId()).isPresent())
            throw new BusinessException(QuizErrorCode.QUIZ_ALREADY_ATTEMPTED);
        return Optional.empty();
    }
    private void requireMember(Long id) {
        if (!members.existsById(id)) throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
    }
}
