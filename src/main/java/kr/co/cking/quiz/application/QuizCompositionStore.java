package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.quiz.application.dto.QuizCompositionCommand;
import kr.co.cking.quiz.application.dto.QuizQuestionContent;
import kr.co.cking.quiz.domain.*;
import kr.co.cking.quiz.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.*;
import java.util.stream.Collectors;

/** Quiz 잠금 이후 replay를 먼저 판정한다. Revision·문항·접수는 함께 커밋하거나 함께 롤백한다. */
@Service @RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
public class QuizCompositionStore {
    private final QuizCreatorLookup creatorLookup;
    private final QuizRepository quizzes;
    private final QuizRevisionRepository revisions;
    private final QuizQuestionRepository questions;
    private final QuizOptionRepository options;
    private final QuizCompositionRequestRepository requests;
    private final QuizRevisionReader reader;
    private final Clock clock;
    public record Admission(Long revisionId, boolean created) {}
    private record Selected(QuizQuestionContent content, QuizQuestionChangeType type, Long origin, Long previous) {}

    public Admission compose(Long memberId, Long quizId, QuizCompositionCommand command) {
        var quiz = owned(memberId, quizId, true);
        var existing = replay(quiz, command);
        if (existing.isPresent()) return existing.get();
        quiz.requireNotClosed();
        if (quiz.isContentLocked()) throw error(QuizErrorCode.QUIZ_CONTENT_LOCKED);
        var parent = revisions.findById(command.baseRevisionId()).filter(r -> r.getQuizId().equals(quizId))
                .orElseThrow(() -> error(QuizErrorCode.QUIZ_REVISION_NOT_FOUND));
        var latest = revisions.findTopByQuizIdOrderByRevisionNoDesc(quizId).orElseThrow(() -> error(QuizErrorCode.QUIZ_REVISION_NOT_FOUND));
        if (!latest.getRevisionId().equals(parent.getRevisionId()) || !parent.getRevisionSha256().equals(command.baseRevisionSha256()))
            throw error(QuizErrorCode.QUIZ_REVISION_CONFLICT);
        var baseContents = reader.readVerified(quiz, parent);
        var baseRows = questions.findByRevisionIdOrderByQuestionNoAsc(parent.getRevisionId());
        var rowById = baseRows.stream().collect(Collectors.toMap(QuizQuestion::getQuestionId, q -> q));
        var selected = new ArrayList<Selected>();
        var duplicateQuestions = new HashSet<String>();
        for (var selection : command.questions()) {
            var previous = selection.sourceQuestionId() == null ? null : rowById.get(selection.sourceQuestionId());
            if (selection.sourceQuestionId() != null && previous == null) throw error(CommonErrorCode.VALIDATION_FAILED);
            var supplied = selection.content();
            var content = supplied == null ? baseContents.get(previous.getQuestionNo())
                    : new QuizQuestionContent(selected.size(), supplied.question(), supplied.options(), supplied.correctOptionIndex(), supplied.explanation(), supplied.sourceEvidence());
            // 복사한 원본도 최종 집합의 4지선다·문자열 계약으로 검증한다. Judge 판정은 선택을 막지 않는다.
            new QuizCompositionCommand.Content(content.question(), content.options(), content.correctOptionIndex(), content.explanation(), content.sourceEvidence());
            if (!duplicateQuestions.add(QuizCompositionCommand.duplicateKey(content.question()))) throw error(CommonErrorCode.VALIDATION_FAILED);
            Long origin = previous == null ? null : previous.getChangeType() == null ? previous.getQuestionId() : previous.getOriginQuestionId();
            var type = QuizQuestionChangeType.CREATOR_ADDED;
            if (origin != null) {
                var original = questions.findById(origin).orElseThrow(() -> error(QuizErrorCode.QUIZ_REVISION_INTEGRITY_FAILED));
                var originRevision = revisions.findById(original.getRevisionId()).filter(r -> r.getQuizId().equals(quizId) && !r.isCreatorComposed())
                        .orElseThrow(() -> error(QuizErrorCode.QUIZ_REVISION_INTEGRITY_FAILED));
                var originContent = reader.readVerified(quiz, originRevision).get(original.getQuestionNo());
                type = QuizQuestionChangeType.ofAiQuestion(originContent.toFingerprintQuestion(), content.toFingerprintQuestion());
            }
            selected.add(new Selected(content, type, origin, previous == null ? null : previous.getQuestionId()));
        }
        var hashQuestions = selected.stream().map(s -> new QuizRevisionFingerprint.ComposedQuestion(s.type(), s.origin(), s.previous(), s.content().toFingerprintQuestion())).toList();
        var hash = QuizRevisionFingerprint.revisionSha256V2(quiz.getContentTextSha256(), parent.getTitle(), parent.getPromptVersion(), parent.getRevisionId(), parent.getRevisionSha256(), hashQuestions);
        var now = clock.instant();
        var revision = revisions.saveAndFlush(QuizRevision.composed(parent, parent.getRevisionNo()+1, parent.getTitle(), selected.size(), 4, hash, memberId, now));
        for (int i=0; i<selected.size(); i++) {
            var s=selected.get(i); var c=s.content();
            var row=questions.save(QuizQuestion.composed(revision.getRevisionId(),i,c.question(),c.correctOptionIndex(),c.explanation(),c.sourceEvidence(),s.type(),s.origin(),s.previous()));
            for(int j=0;j<4;j++) options.save(new QuizOption(row.getQuestionId(),j,c.options().get(j)));
        }
        requests.saveAndFlush(new QuizCompositionRequest(quizId,command.requestId().toString(),command.fingerprint(),revision.getRevisionId(),memberId,now));
        return new Admission(revision.getRevisionId(),true);
    }
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public Optional<Admission> findExisting(Long memberId, Long quizId, QuizCompositionCommand command) {
        return replay(owned(memberId,quizId,false),command);
    }
    private Optional<Admission> replay(Quiz quiz, QuizCompositionCommand command) {
        return requests.findByQuizIdAndRequestId(quiz.getQuizId(),command.requestId().toString()).map(request -> {
            if(!request.getPayloadSha256().equals(command.fingerprint())) throw error(QuizErrorCode.QUIZ_EDIT_REQUEST_CONFLICT);
            var result=revisions.findById(request.getRevisionId()).filter(r->r.getQuizId().equals(quiz.getQuizId()))
                    .orElseThrow(()->error(QuizErrorCode.QUIZ_REVISION_INTEGRITY_FAILED));
            reader.readVerified(quiz,result);
            return new Admission(result.getRevisionId(),false);
        });
    }
    private Quiz owned(Long memberId,Long quizId,boolean lock) {
        var creator=creatorLookup.requireCreator(memberId);
        var quiz=(lock?quizzes.findByIdForUpdate(quizId):quizzes.findById(quizId)).orElseThrow(()->error(QuizErrorCode.QUIZ_NOT_FOUND));
        if(!quiz.isOwnedBy(creator.getCreatorId())) throw error(CommonErrorCode.FORBIDDEN);
        return quiz;
    }
    private static BusinessException error(kr.co.cking.common.exception.ErrorCode code){return new BusinessException(code);}
}
