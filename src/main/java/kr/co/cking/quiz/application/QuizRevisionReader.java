package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.quiz.application.dto.QuizQuestionContent;
import kr.co.cking.quiz.domain.Quiz;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizOption;
import kr.co.cking.quiz.domain.QuizQuestion;
import kr.co.cking.quiz.domain.QuizQuestionChangeType;
import kr.co.cking.quiz.domain.QuizRevision;
import kr.co.cking.quiz.domain.QuizRevisionFingerprint;
import kr.co.cking.quiz.repository.QuizOptionRepository;
import kr.co.cking.quiz.repository.QuizQuestionRepository;
import kr.co.cking.quiz.repository.QuizRevisionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** revision의 문항·보기를 저장 순서대로 읽고, 승인·게시 전에는 저장 내용을 revision 해시와 대조한다. */
@Slf4j
@Component
@RequiredArgsConstructor
class QuizRevisionReader {

    private final QuizQuestionRepository questionRepository;
    private final QuizOptionRepository optionRepository;
    private final QuizRevisionRepository revisionRepository;

    List<QuizQuestionContent> read(Long revisionId) {
        List<QuizQuestion> questions = questionRepository.findByRevisionIdOrderByQuestionNoAsc(revisionId);
        return toContents(questions, optionsByQuestion(questions));
    }

    /**
     * 원본 텍스트 해시와 저장된 문항으로 revision 해시를 다시 계산해, 생성 시 기록한 값과 다르면
     * {@code QUIZ_REVISION_INTEGRITY_FAILED}로 막는다. 해시에 들어가지 않는 문항·보기 번호와 보기 수도 함께 확인한다.
     * AI 생성 revision은 v1, Creator가 구성한 revision은 v2 규칙으로 계산하고 문항 계보도 검증한다.
     */
    List<QuizQuestionContent> readVerified(Quiz quiz, QuizRevision revision) {
        List<QuizQuestion> questionRows = questionRepository.findByRevisionIdOrderByQuestionNoAsc(revision.getRevisionId());
        Map<Long, List<QuizOption>> optionRows = optionsByQuestion(questionRows);
        List<QuizQuestionContent> questions = toContents(questionRows, optionRows);
        boolean intact = quiz.hasIntactContentText()
                && hasExpectedNumbering(revision, questionRows, optionRows)
                && (revision.isCreatorComposed()
                        ? hasIntactComposition(quiz, revision, questionRows, questions)
                        : QuizRevisionFingerprint.VERSION.equals(revision.getHashVersion())
                                && questionRows.stream().allMatch(question -> question.getChangeType() == null
                                        && question.getOriginQuestionId() == null
                                        && question.getPreviousQuestionId() == null)
                                && revisionSha256(quiz.getContentTextSha256(), revision.getTitle(),
                                        revision.getPromptVersion(), questions).equals(revision.getRevisionSha256()));
        if (!intact) {
            log.error("저장된 Quiz 내용이 revision 해시와 다릅니다. quizId={}, revisionId={}",
                    quiz.getQuizId(), revision.getRevisionId());
            throw new BusinessException(QuizErrorCode.QUIZ_REVISION_INTEGRITY_FAILED);
        }
        return questions;
    }

    /**
     * Creator가 구성한 revision의 v2 해시와 문항 계보를 검증한다. 직전 문항은 부모 revision의 서로 다른 문항이어야 하고,
     * 원본 문항은 같은 Quiz의 AI 생성 revision 원본 문항이어야 하며 직전 문항의 원본과 같아야 한다. 변경 유형은 Creator의
     * 주장이 아니라 원본과의 내용 비교로 정해진 값이어야 한다. 추가 문항은 원본이 없고 직전 문항도 추가 문항이어야 한다.
     */
    private boolean hasIntactComposition(Quiz quiz, QuizRevision revision, List<QuizQuestion> rows,
                                         List<QuizQuestionContent> contents) {
        if (!QuizRevisionFingerprint.VERSION_V2.equals(revision.getHashVersion())
                || rows.stream().anyMatch(row -> row.getChangeType() == null)) {
            return false;
        }
        List<QuizRevisionFingerprint.ComposedQuestion> composed = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            QuizQuestion row = rows.get(index);
            composed.add(new QuizRevisionFingerprint.ComposedQuestion(row.getChangeType(), row.getOriginQuestionId(),
                    row.getPreviousQuestionId(), contents.get(index).toFingerprintQuestion()));
        }
        String expected = QuizRevisionFingerprint.revisionSha256V2(quiz.getContentTextSha256(), revision.getTitle(),
                revision.getPromptVersion(), revision.getParentRevisionId(), revision.getParentRevisionSha256(), composed);
        return expected.equals(revision.getRevisionSha256()) && hasValidLineage(quiz, revision, rows, contents);
    }

    private boolean hasValidLineage(Quiz quiz, QuizRevision revision, List<QuizQuestion> rows,
                                    List<QuizQuestionContent> contents) {
        Set<Long> previousIds = new HashSet<>();
        Set<Long> referenceIds = new HashSet<>();
        for (QuizQuestion row : rows) {
            if (row.getPreviousQuestionId() != null && !previousIds.add(row.getPreviousQuestionId())) {
                return false;
            }
            referenceIds.add(row.getPreviousQuestionId());
            referenceIds.add(row.getOriginQuestionId());
        }
        referenceIds.remove(null);
        Map<Long, QuizQuestion> references = questionRepository.findAllById(referenceIds).stream()
                .collect(Collectors.toMap(QuizQuestion::getQuestionId, Function.identity()));
        if (!references.keySet().equals(referenceIds)) {
            return false;
        }
        List<QuizQuestion> origins = rows.stream().map(QuizQuestion::getOriginQuestionId).filter(Objects::nonNull)
                .distinct().map(references::get).toList();
        Map<Long, QuizRevision> originRevisions = revisionRepository
                .findAllById(origins.stream().map(QuizQuestion::getRevisionId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(QuizRevision::getRevisionId, Function.identity()));
        Map<Long, List<QuizOption>> originOptions = optionsByQuestion(origins);
        for (QuizQuestion origin : origins) {
            QuizRevision originRevision = originRevisions.get(origin.getRevisionId());
            if (origin.getChangeType() != null || originRevision == null || originRevision.isCreatorComposed()
                    || !originRevision.getQuizId().equals(quiz.getQuizId())) {
                return false;
            }
        }
        for (int index = 0; index < rows.size(); index++) {
            QuizQuestion row = rows.get(index);
            QuizQuestion previous = row.getPreviousQuestionId() == null ? null : references.get(row.getPreviousQuestionId());
            if (previous != null && !previous.getRevisionId().equals(revision.getParentRevisionId())) {
                return false;
            }
            if (row.getChangeType() == QuizQuestionChangeType.CREATOR_ADDED) {
                if (previous != null && previous.getChangeType() != QuizQuestionChangeType.CREATOR_ADDED) {
                    return false;
                }
                continue;
            }
            if (previous == null) {
                return false;
            }
            Long expectedOrigin = previous.getChangeType() == null ? previous.getQuestionId() : previous.getOriginQuestionId();
            if (previous.getChangeType() == QuizQuestionChangeType.CREATOR_ADDED
                    || !row.getOriginQuestionId().equals(expectedOrigin)) {
                return false;
            }
            QuizQuestion origin = references.get(row.getOriginQuestionId());
            QuizRevisionFingerprint.Question originContent = toContents(List.of(origin), originOptions).get(0)
                    .toFingerprintQuestion();
            if (row.getChangeType() != QuizQuestionChangeType.ofAiQuestion(originContent,
                    contents.get(index).toFingerprintQuestion())) {
                return false;
            }
        }
        return true;
    }

    /** 문항 번호가 0부터 문항 수-1까지, 문항마다 보기 번호가 0부터 보기 수-1까지 빠짐없이 이어지는지 확인한다. */
    private boolean hasExpectedNumbering(QuizRevision revision, List<QuizQuestion> questions,
                                         Map<Long, List<QuizOption>> optionsByQuestion) {
        if (questions.size() != revision.getQuestionCount()) {
            return false;
        }
        for (int questionNo = 0; questionNo < questions.size(); questionNo++) {
            QuizQuestion question = questions.get(questionNo);
            List<QuizOption> options = optionsByQuestion.getOrDefault(question.getQuestionId(), List.of());
            if (question.getQuestionNo() != questionNo || options.size() != revision.getOptionCount()) {
                return false;
            }
            for (int optionNo = 0; optionNo < options.size(); optionNo++) {
                if (options.get(optionNo).getOptionNo() != optionNo) {
                    return false;
                }
            }
        }
        return true;
    }

    private Map<Long, List<QuizOption>> optionsByQuestion(List<QuizQuestion> questions) {
        if (questions.isEmpty()) {
            return Map.of();
        }
        return optionRepository
                .findByQuestionIdInOrderByQuestionIdAscOptionNoAsc(
                        questions.stream().map(QuizQuestion::getQuestionId).toList())
                .stream()
                .collect(Collectors.groupingBy(QuizOption::getQuestionId));
    }

    private List<QuizQuestionContent> toContents(List<QuizQuestion> questions,
                                                 Map<Long, List<QuizOption>> optionsByQuestion) {
        return questions.stream()
                .map(question -> new QuizQuestionContent(
                        question.getQuestionNo(),
                        question.getQuestionText(),
                        optionsByQuestion.getOrDefault(question.getQuestionId(), List.of()).stream()
                                .map(QuizOption::getOptionText)
                                .toList(),
                        question.getCorrectOptionIndex(),
                        question.getExplanation(),
                        question.getSourceEvidence()))
                .toList();
    }

    static String revisionSha256(String contentTextSha256, String title, String promptVersion,
                                 List<QuizQuestionContent> questions) {
        return QuizRevisionFingerprint.revisionSha256(contentTextSha256, title, promptVersion,
                questions.stream().map(QuizQuestionContent::toFingerprintQuestion).toList());
    }
}
