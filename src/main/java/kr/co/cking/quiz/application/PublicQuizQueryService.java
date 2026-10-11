package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.quiz.application.dto.PublicQuizViews;
import kr.co.cking.quiz.domain.Quiz;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizRevision;
import kr.co.cking.quiz.repository.PublishedQuizSummary;
import kr.co.cking.quiz.repository.QuizRepository;
import kr.co.cking.quiz.repository.QuizRevisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 게시된 Quiz 공개 조회(이슈 #521). 로그인하지 않아도 조회할 수 있다.
 *
 * <p>게시 revision만 반환하며 정답 관련 정보는 {@link PublicQuizViews}로 옮기지 않는다. 미게시·게시 취소된 Quiz는
 * 목록에서 빠지고 상세는 {@code QUIZ_NOT_FOUND}다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicQuizQueryService {

    private final CreatorRepository creatorRepository;
    private final QuizRepository quizRepository;
    private final QuizRevisionRepository revisionRepository;
    private final QuizRevisionReader revisionReader;

    /** 최근 게시 순(publishedAt 내림차순, quizId 내림차순). 없는 Creator는 {@code RESOURCE_NOT_FOUND}. */
    public Page<PublicQuizViews.Summary> findPublished(Long creatorId, Pageable pageable) {
        requireCreator(creatorId);
        Page<PublishedQuizSummary> quizzes = quizRepository.findPublishedByCreatorId(creatorId, pageable);
        Map<Long, QuizRevision> revisions = quizzes.isEmpty()
                ? Map.of()
                : revisionRepository.findAllById(quizzes.map(PublishedQuizSummary::publishedRevisionId).toList()).stream()
                        .collect(Collectors.toMap(QuizRevision::getRevisionId, Function.identity()));
        return quizzes.map(quiz -> {
            QuizRevision revision = revisions.get(quiz.publishedRevisionId());
            return new PublicQuizViews.Summary(quiz.quizId(), revision.getRevisionId(), revision.getTitle(),
                    revision.getQuestionCount(), quiz.publishedAt());
        });
    }

    public PublicQuizViews.Detail findPublishedDetail(Long creatorId, Long quizId) {
        requireCreator(creatorId);
        Quiz quiz = quizRepository.findById(quizId)
                .filter(candidate -> candidate.isOwnedBy(creatorId) && candidate.isPublished())
                .orElseThrow(() -> new BusinessException(QuizErrorCode.QUIZ_NOT_FOUND));
        QuizRevision revision = revisionRepository.findById(quiz.getPublishedRevisionId())
                .orElseThrow(() -> new IllegalStateException("게시 revision이 없습니다. quizId=" + quizId));
        return new PublicQuizViews.Detail(
                quiz.getQuizId(),
                quiz.getCreatorId(),
                revision.getRevisionId(),
                quiz.getSourceVideoId(),
                revision.getTitle(),
                quiz.getPublishedAt(),
                revisionReader.read(revision.getRevisionId()).stream()
                        .map(question -> new PublicQuizViews.Question(
                                question.questionNo(), question.question(), question.options()))
                        .toList());
    }

    private void requireCreator(Long creatorId) {
        if (!creatorRepository.existsById(creatorId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
    }
}
