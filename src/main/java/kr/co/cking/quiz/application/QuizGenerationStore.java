package kr.co.cking.quiz.application;

import kr.co.cking.quiz.application.config.QuizGenerationProperties;
import kr.co.cking.quiz.application.dto.QuizDraft;
import kr.co.cking.quiz.application.dto.QuizDraftCommand;
import kr.co.cking.quiz.domain.QuizGeneration;
import kr.co.cking.quiz.domain.QuizGenerationFailure;
import kr.co.cking.quiz.domain.QuizGenerationStatus;
import kr.co.cking.quiz.repository.QuizGenerationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Quiz 생성 작업의 짧은 DB Transaction 모음(이슈 #525). 메서드 하나가 Transaction 하나이며, Provider 호출은 이
 * 밖에서 한다.
 */
@Component
@RequiredArgsConstructor
public class QuizGenerationStore {

    private final QuizGenerationRepository repository;
    private final QuizDraftService draftService;
    private final QuizGenerationProperties properties;

    @Transactional(readOnly = true)
    public Optional<QuizGeneration> find(Long generationId) {
        return repository.findById(generationId);
    }

    /** 고유 제약 충돌 뒤 다시 읽을 때도 새 Transaction에서 읽는다. */
    @Transactional(readOnly = true)
    public Optional<QuizGeneration> findByRequest(Long creatorId, String requestId) {
        return repository.findByCreatorIdAndRequestId(creatorId, requestId);
    }

    /** requestId·진행 중 작업 고유 제약을 어기면 커밋 전에 {@code DataIntegrityViolationException}이 난다. */
    @Transactional
    public QuizGeneration create(QuizGeneration generation) {
        return repository.saveAndFlush(generation);
    }

    /** Creator의 오래된 PENDING은 FAILED, 오래된 RUNNING은 UNKNOWN으로 끝낸다. Provider를 다시 호출하지 않는다. */
    @Transactional
    public void expireStale(Long creatorId, Instant now) {
        repository.expirePending(creatorId, now.minus(properties.getStalePending()), QuizGenerationStatus.PENDING,
                QuizGenerationStatus.FAILED, QuizGenerationFailure.STALE_PENDING, now);
        repository.expireRunning(creatorId, now.minus(properties.getStaleRunning()), QuizGenerationStatus.RUNNING,
                QuizGenerationStatus.UNKNOWN, QuizGenerationFailure.STALE_RUNNING, now);
    }

    @Transactional
    public boolean claim(Long generationId, Instant now) {
        return repository.claim(generationId, QuizGenerationStatus.PENDING, QuizGenerationStatus.RUNNING, now) == 1;
    }

    @Transactional
    public boolean failPending(Long generationId, QuizGenerationFailure failure, Instant now) {
        return repository.finish(generationId, QuizGenerationStatus.PENDING, QuizGenerationStatus.FAILED,
                failure, now) == 1;
    }

    /** RUNNING일 때만 FAILED·UNKNOWN으로 끝낸다. 이미 정리된 작업은 그대로 둔다. */
    @Transactional
    public boolean finishRunning(Long generationId, QuizGenerationStatus status, QuizGenerationFailure failure,
                                 Instant now) {
        return repository.finish(generationId, QuizGenerationStatus.RUNNING, status, failure, now) == 1;
    }

    /**
     * 작업 행을 잠근 뒤 아직 RUNNING일 때만 Quiz를 저장하고 SUCCEEDED로 바꾼다. 둘은 한 Transaction이라 함께
     * 커밋되거나 함께 롤백된다. 그 사이 UNKNOWN·FAILED로 정리된 작업은 저장하지 않고 빈 값을 돌려준다.
     */
    @Transactional
    public Optional<QuizDraft> completeRunning(Long generationId, QuizDraftCommand command, Instant now) {
        QuizGeneration generation = repository.findByIdForUpdate(generationId)
                .orElseThrow(() -> new IllegalStateException("Quiz 생성 작업이 없습니다. generationId=" + generationId));
        if (generation.getStatus() != QuizGenerationStatus.RUNNING) {
            return Optional.empty();
        }
        QuizDraft draft = draftService.create(command);
        generation.succeed(draft.quizId(), draft.revisionId(), now);
        return Optional.of(draft);
    }
}
