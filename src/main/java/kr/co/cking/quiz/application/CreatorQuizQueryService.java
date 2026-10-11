package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.quiz.application.dto.CreatorQuizViews;
import kr.co.cking.quiz.domain.Quiz;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizReviewStatus;
import kr.co.cking.quiz.domain.QuizRevision;
import kr.co.cking.quiz.domain.QuizRevisionDecision;
import kr.co.cking.quiz.repository.QuizRepository;
import kr.co.cking.quiz.repository.QuizRevisionDecisionRepository;
import kr.co.cking.quiz.repository.QuizRevisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Creator 본인 Quiz 조회(이슈 #521). 정답·해설·근거·원본 텍스트를 포함한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CreatorQuizQueryService {

    private final QuizCreatorLookup creatorLookup;
    private final QuizRepository quizRepository;
    private final QuizRevisionRepository revisionRepository;
    private final QuizRevisionDecisionRepository decisionRepository;
    private final QuizRevisionReader revisionReader;

    /** 최근 생성 순(createdAt 내림차순, quizId 내림차순). */
    public Page<CreatorQuizViews.Summary> findMine(Long memberId, Pageable pageable) {
        Creator creator = creatorLookup.requireCreator(memberId);
        Page<Quiz> quizzes = quizRepository.findByCreatorIdLatestFirst(creator.getCreatorId(), pageable);
        if (quizzes.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, quizzes.getTotalElements());
        }
        Map<Long, QuizRevision> latestByQuiz = revisionRepository
                .findByQuizIdIn(quizzes.map(Quiz::getQuizId).toList()).stream()
                .collect(Collectors.toMap(QuizRevision::getQuizId, Function.identity(),
                        (left, right) -> left.getRevisionNo() >= right.getRevisionNo() ? left : right));
        Map<Long, QuizRevisionDecision> decisions = decisionsByRevision(
                latestByQuiz.values().stream().map(QuizRevision::getRevisionId).toList());
        return quizzes.map(quiz -> {
            QuizRevision latest = latestByQuiz.get(quiz.getQuizId());
            return new CreatorQuizViews.Summary(
                    quiz.getQuizId(),
                    latest.getTitle(),
                    quiz.getPublicationStatus(),
                    quiz.getPublishedRevisionId(),
                    latest.getRevisionId(),
                    latest.getRevisionNo(),
                    QuizReviewStatus.of(decisions.get(latest.getRevisionId())),
                    quiz.getPublishedAt(),
                    quiz.getCreatedAt(),
                    quiz.getUpdatedAt());
        });
    }

    public CreatorQuizViews.Detail findDetail(Long memberId, Long quizId) {
        Quiz quiz = requireOwnedQuiz(memberId, quizId);
        List<QuizRevision> revisions = revisionRepository.findByQuizIdOrderByRevisionNoAsc(quizId);
        Map<Long, QuizRevisionDecision> decisions = decisionsByRevision(
                revisions.stream().map(QuizRevision::getRevisionId).toList());
        return new CreatorQuizViews.Detail(
                quiz.getQuizId(),
                quiz.getCreatorId(),
                quiz.getSourceVideoId(),
                quiz.getPublicationStatus(),
                quiz.getPublishedRevisionId(),
                quiz.getPublishedAt(),
                quiz.getUnpublishedAt(),
                quiz.getCreatedAt(),
                quiz.getUpdatedAt(),
                revisions.stream()
                        .map(revision -> {
                            QuizRevisionDecision decision = decisions.get(revision.getRevisionId());
                            return new CreatorQuizViews.RevisionSummary(
                                    revision.getRevisionId(),
                                    revision.getRevisionNo(),
                                    revision.getTitle(),
                                    QuizReviewStatus.of(decision),
                                    decision == null ? null : decision.getDecidedAt(),
                                    revision.getCreatedAt());
                        })
                        .toList());
    }

    public CreatorQuizViews.Revision findRevision(Long memberId, Long quizId, Long revisionId) {
        Quiz quiz = requireOwnedQuiz(memberId, quizId);
        List<QuizRevision> revisions = revisionRepository.findByQuizIdOrderByRevisionNoAsc(quizId);
        QuizRevision revision = revisions.stream()
                .filter(candidate -> candidate.getRevisionId().equals(revisionId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(QuizErrorCode.QUIZ_REVISION_NOT_FOUND));
        QuizRevision latest = revisions.stream().max(Comparator.comparingInt(QuizRevision::getRevisionNo)).orElseThrow();
        QuizRevisionDecision decision = decisionRepository.findByRevisionId(revisionId).orElse(null);
        return new CreatorQuizViews.Revision(
                quiz.getQuizId(),
                revision.getRevisionId(),
                revision.getRevisionNo(),
                latest.getRevisionId().equals(revisionId),
                quiz.isPublishing(revisionId),
                revision.getTitle(),
                quiz.getSourceVideoId(),
                revision.getPromptVersion(),
                quiz.getContentText(),
                QuizReviewStatus.of(decision),
                decision == null ? null : decision.getReason(),
                decision == null ? null : decision.getDecidedAt(),
                revision.getCreatedAt(),
                revisionReader.read(revisionId));
    }

    /** 없는 Quiz는 404, 다른 Creator의 Quiz는 403. */
    private Quiz requireOwnedQuiz(Long memberId, Long quizId) {
        Creator creator = creatorLookup.requireCreator(memberId);
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new BusinessException(QuizErrorCode.QUIZ_NOT_FOUND));
        if (!quiz.isOwnedBy(creator.getCreatorId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return quiz;
    }

    private Map<Long, QuizRevisionDecision> decisionsByRevision(List<Long> revisionIds) {
        if (revisionIds.isEmpty()) {
            return Map.of();
        }
        return decisionRepository.findByRevisionIdIn(revisionIds).stream()
                .collect(Collectors.toMap(QuizRevisionDecision::getRevisionId, Function.identity()));
    }
}
