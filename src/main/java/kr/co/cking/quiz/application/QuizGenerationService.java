package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.quiz.application.config.QuizGenerationProperties;
import kr.co.cking.quiz.application.dto.QuizGenerationCommand;
import kr.co.cking.quiz.application.dto.QuizGenerationView;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizGeneration;
import kr.co.cking.quiz.domain.QuizGenerationFailure;
import kr.co.cking.quiz.domain.QuizRevision;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
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
            return replay(existing.get(), fingerprint);
        }
        if (!properties.isEnabled()) {
            throw new BusinessException(QuizErrorCode.QUIZ_GENERATION_DISABLED);
        }
        Instant now = clock.instant();
        store.expireStale(creator.getCreatorId(), now);
        QuizGeneration created;
        try {
            created = store.create(new QuizGeneration(creator.getCreatorId(), memberId, requestId, videoId, title,
                    questionCount, now));
        } catch (DataIntegrityViolationException exception) {
            return store.findByRequest(creator.getCreatorId(), requestId)
                    .map(generation -> replay(generation, fingerprint))
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
        QuizGeneration generation = store.find(generationId)
                .orElseThrow(() -> new BusinessException(QuizErrorCode.QUIZ_GENERATION_NOT_FOUND));
        if (!generation.isOwnedBy(creator.getCreatorId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        store.expireStale(creator.getCreatorId(), clock.instant());
        return QuizGenerationView.of(store.find(generationId).orElse(generation));
    }

    private QuizGenerationView replay(QuizGeneration generation, String fingerprint) {
        if (!generation.getRequestFingerprint().equals(fingerprint)) {
            throw new BusinessException(QuizErrorCode.QUIZ_GENERATION_REQUEST_CONFLICT);
        }
        return QuizGenerationView.of(generation);
    }

    private String requireTitle(String title) {
        if (title == null || title.isBlank() || title.length() > QuizRevision.MAX_TITLE_LENGTH) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        return title;
    }
}
