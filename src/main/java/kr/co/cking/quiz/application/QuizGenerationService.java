package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.quiz.application.config.QuizGenerationProperties;
import kr.co.cking.quiz.application.dto.QuizGenerationCommand;
import kr.co.cking.quiz.application.dto.QuizGenerationCurrentView;
import kr.co.cking.quiz.application.dto.QuizGenerationView;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizGeneration;
import kr.co.cking.quiz.domain.QuizGenerationFailure;
import kr.co.cking.quiz.domain.QuizGenerationReviewStage;
import kr.co.cking.quiz.domain.QuizRevision;
import kr.co.cking.quiz.grounding.GroundingFact;
import kr.co.cking.quiz.grounding.VideoGroundingValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * YouTube URL 기반 Quiz 생성 요청 접수와 상태 조회(이슈 #525).
 *
 * <p>이 Service는 Transaction을 열지 않는다. 각 DB 작업은 {@link QuizGenerationStore}의 짧은 Transaction이며, 작업
 * 저장이 커밋된 뒤에 Executor에 제출한다.
 *
 * <ul>
 *   <li>같은 requestId·같은 입력(videoId·제목·문항 수·보기 수)은 기존 작업을 돌려주고, 다른 입력은 409다. 기능이
 *       꺼져 있어도 기존 작업 재요청은 같은 응답이다</li>
 *   <li>기능이 꺼져 있으면 새 요청은 503이다</li>
 *   <li>Creator당 PENDING·RUNNING 작업은 하나다. 오래된 작업을 먼저 정리하고, 동시 요청은 DB 고유 제약이 막는다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class QuizGenerationService {

    private static final int DEFAULT_QUESTION_COUNT = 3;
    private static final Set<Integer> QUESTION_COUNTS = Set.of(3, 5);

    private final QuizCreatorLookup creatorLookup;
    private final QuizGenerationStore store;
    private final QuizGenerationDispatcher dispatcher;
    private final QuizGenerationProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public QuizGenerationView request(Long memberId, QuizGenerationCommand command) {
        Creator creator = creatorLookup.requireCreator(memberId);
        if (command.requestId() == null) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        String videoId = YoutubeVideoUrl.videoIdOf(command.youtubeUrl());
        String title = requireTitle(command.title());
        int questionCount = command.questionCount() == null ? DEFAULT_QUESTION_COUNT : command.questionCount();
        if (!QUESTION_COUNTS.contains(questionCount)) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        String requestId = command.requestId().toString();
        String fingerprint = QuizGeneration.fingerprint(videoId, title, questionCount, QuizGeneration.OPTION_COUNT);

        Optional<QuizGeneration> existing = store.findByRequest(creator.getCreatorId(), requestId);
        if (existing.isPresent()) {
            return replay(creator.getCreatorId(), existing.get(), fingerprint);
        }
        if (!properties.isEnabled()) {
            throw new BusinessException(QuizErrorCode.QUIZ_GENERATION_DISABLED);
        }
        Instant now = clock.instant();
        store.expireStale(creator.getCreatorId(), now);
        QuizGeneration created;
        try {
            created = store.create(new QuizGeneration(creator.getCreatorId(), memberId, requestId, videoId, title,
                    questionCount, now, properties.getContentReview().isEnabled()));
        } catch (DataIntegrityViolationException exception) {
            return store.findByRequest(creator.getCreatorId(), requestId)
                    .map(generation -> replay(creator.getCreatorId(), generation, fingerprint))
                    .orElseThrow(() -> new BusinessException(QuizErrorCode.QUIZ_GENERATION_IN_PROGRESS));
        }
        Long generationId = created.getGenerationId();
        if (!dispatcher.dispatch(generationId)) {
            store.failPending(generationId, QuizGenerationFailure.DISPATCH_REJECTED, clock.instant());
        }
        return QuizGenerationView.of(store.find(generationId).orElse(created));
    }

    /** 다른 Creator의 작업은 403, 없는 작업은 404. 조회하면서 오래된 작업을 정리한다. */
    public QuizGenerationView find(Long memberId, Long generationId) {
        Creator creator = creatorLookup.requireCreator(memberId);
        QuizGeneration generation = owned(creator, generationId);
        store.expireStale(creator.getCreatorId(), clock.instant());
        return QuizGenerationView.of(store.find(generationId).orElse(generation));
    }

    /**
     * Creator의 진행 중(PENDING·RUNNING) 작업을 찾는다(이슈 #615). 없으면 빈 값이다. 조회 전에 오래된 작업을 정리한다.
     * 원문 검토 대기면 검토할 AI 원문·facts를 함께 돌려주고, 그 밖의 단계와 기존 작업은 원문을 담지 않는다.
     */
    public Optional<QuizGenerationCurrentView> findCurrent(Long memberId) {
        Creator creator = creatorLookup.requireCreator(memberId);
        store.expireStale(creator.getCreatorId(), clock.instant());
        return store.findActive(creator.getCreatorId())
                .map(generation -> new QuizGenerationCurrentView(QuizGenerationView.of(generation), content(generation)));
    }

    private QuizGenerationCurrentView.Content content(QuizGeneration generation) {
        if (generation.getReviewStage() != QuizGenerationReviewStage.AWAITING_REVIEW) {
            return null;
        }
        List<GroundingFact> facts = objectMapper.readValue(generation.getGroundingFacts(), new TypeReference<>() {
        });
        return new QuizGenerationCurrentView.Content(generation.getAiContentText(), facts,
                generation.getGroundingModel(), generation.getGroundingPromptVersion(),
                VideoGroundingValidator.MAX_CONTENT_TEXT_LENGTH);
    }

    /**
     * Creator가 검토한 원문을 확정하고 Quiz 생성 단계를 제출한다(이슈 #604). 원문은 정규화하지 않고 비어 있지 않은
     * 50,000자 이하여야 한다. 같은 원문으로 이미 확정한 작업은 다시 제출하지 않고 현재 상태를 돌려준다. 확정은 생성
     * Provider를 호출하므로 생성 기능이 꺼져 있으면 새 확정은 503이다. 제출이 거절되면 작업을 끝내 Creator 슬롯을 푼다.
     */
    public QuizGenerationView confirmContent(Long memberId, Long generationId, String contentText) {
        Creator creator = creatorLookup.requireCreator(memberId);
        QuizGeneration generation = owned(creator, generationId);
        if (contentText == null || contentText.isBlank()
                || contentText.length() > VideoGroundingValidator.MAX_CONTENT_TEXT_LENGTH) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        boolean replay = contentText.equals(generation.getReviewedContentText());
        if (!replay && !properties.isEnabled()) {
            throw new BusinessException(QuizErrorCode.QUIZ_GENERATION_DISABLED);
        }
        store.expireStale(creator.getCreatorId(), clock.instant());
        QuizGenerationStore.Confirmation confirmation =
                store.confirmContent(generationId, memberId, contentText, clock.instant());
        if (confirmation == QuizGenerationStore.Confirmation.CONFIRMED && !dispatcher.dispatchGeneration(generationId)) {
            store.failUnstartedGeneration(generationId, QuizGenerationFailure.DISPATCH_REJECTED, clock.instant());
        }
        return QuizGenerationView.of(store.find(generationId).orElse(generation));
    }

    /** 원문 검토 대기 작업을 취소한다(이슈 #604). Provider를 호출하지 않으므로 생성 기능이 꺼져 있어도 된다. */
    public QuizGenerationView cancelReview(Long memberId, Long generationId) {
        Creator creator = creatorLookup.requireCreator(memberId);
        QuizGeneration generation = owned(creator, generationId);
        store.expireStale(creator.getCreatorId(), clock.instant());
        store.cancelReview(generationId, clock.instant());
        return QuizGenerationView.of(store.find(generationId).orElse(generation));
    }

    private QuizGeneration owned(Creator creator, Long generationId) {
        QuizGeneration generation = store.find(generationId)
                .orElseThrow(() -> new BusinessException(QuizErrorCode.QUIZ_GENERATION_NOT_FOUND));
        if (!generation.isOwnedBy(creator.getCreatorId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return generation;
    }

    /** 같은 요청이면 조회처럼 오래된 작업을 정리한 뒤 최신 상태를 돌려준다. 새 작업을 만들거나 제출하지 않는다. */
    private QuizGenerationView replay(Long creatorId, QuizGeneration generation, String fingerprint) {
        if (!generation.getRequestFingerprint().equals(fingerprint)) {
            throw new BusinessException(QuizErrorCode.QUIZ_GENERATION_REQUEST_CONFLICT);
        }
        store.expireStale(creatorId, clock.instant());
        return QuizGenerationView.of(store.find(generation.getGenerationId()).orElse(generation));
    }

    private String requireTitle(String title) {
        if (title == null || title.isBlank() || title.length() > QuizRevision.MAX_TITLE_LENGTH) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        return title;
    }
}
