package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.quiz.application.config.QuizGenerationProperties;
import kr.co.cking.quiz.application.dto.QuizDraft;
import kr.co.cking.quiz.application.dto.QuizDraftCommand;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizGeneration;
import kr.co.cking.quiz.domain.QuizGenerationFailure;
import kr.co.cking.quiz.domain.QuizGenerationReviewStage;
import kr.co.cking.quiz.domain.QuizGenerationStatus;
import kr.co.cking.quiz.domain.QuizRevisionFingerprint;
import kr.co.cking.quiz.repository.QuizGenerationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Quiz 생성 작업의 짧은 DB Transaction 모음(이슈 #525). 메서드 하나가 Transaction 하나이며, Provider 호출은 이
 * 밖에서 한다.
 *
 * <p>원문 검토 흐름(이슈 #604)의 단계 전이도 여기서 한다. 단계 전이는 현재 status·단계를 조건으로 건 UPDATE이고,
 * Creator 확정·취소는 작업 행을 잠근 뒤 바꾼다.
 */
@Component
@RequiredArgsConstructor
public class QuizGenerationStore {

    private final QuizGenerationRepository repository;
    private final QuizDraftService draftService;
    private final QuizGenerationProperties properties;

    /** 원문 확정 결과. REPLAYED는 같은 원문으로 이미 확정한 작업이라 아무것도 바꾸지 않았다는 뜻이다. */
    public enum Confirmation {
        CONFIRMED,
        REPLAYED
    }

    /** 검토 취소 결과. REPLAYED는 이미 취소한 작업이라 아무것도 바꾸지 않았다는 뜻이다. */
    public enum Cancellation {
        CANCELLED,
        REPLAYED
    }

    @Transactional(readOnly = true)
    public Optional<QuizGeneration> find(Long generationId) {
        return repository.findById(generationId);
    }

    /** 고유 제약 충돌 뒤 다시 읽을 때도 새 Transaction에서 읽는다. */
    @Transactional(readOnly = true)
    public Optional<QuizGeneration> findByRequest(Long creatorId, String requestId) {
        return repository.findByCreatorIdAndRequestId(creatorId, requestId);
    }

    /** Creator의 진행 중(PENDING·RUNNING) 작업. 원문 검토 대기·생성 단계도 RUNNING이다. */
    @Transactional(readOnly = true)
    public Optional<QuizGeneration> findActive(Long creatorId) {
        return repository.findFirstByCreatorIdAndStatusInOrderByRequestedAtDesc(creatorId,
                List.of(QuizGenerationStatus.PENDING, QuizGenerationStatus.RUNNING));
    }

    /** requestId·진행 중 작업 고유 제약을 어기면 커밋 전에 {@code DataIntegrityViolationException}이 난다. */
    @Transactional
    public QuizGeneration create(QuizGeneration generation) {
        return repository.saveAndFlush(generation);
    }

    /**
     * Creator의 오래된 작업을 정리한다. Provider를 다시 호출하지 않는다.
     * <ul>
     *   <li>PENDING: 접수 후 stale-pending이 지나면 FAILED(STALE_PENDING)</li>
     *   <li>RUNNING인 기존 흐름·Grounding 단계: 시작 후 stale-running이 지나면 UNKNOWN(STALE_RUNNING)</li>
     *   <li>원문 검토 대기: 자동으로 끝내지 않는다. Creator가 확정하거나 취소한다.</li>
     *   <li>생성 단계 시작 전: 확정 후 stale-pending이 지나면 FAILED(STALE_PENDING). 재시작·dispatch 유실로 남은 작업이다.</li>
     *   <li>생성 단계 시작 후: 생성 시작 후 stale-running이 지나면 UNKNOWN(STALE_RUNNING)</li>
     * </ul>
     */
    @Transactional
    public void expireStale(Long creatorId, Instant now) {
        Instant pendingBefore = now.minus(properties.getStalePending());
        Instant runningBefore = now.minus(properties.getStaleRunning());
        repository.expirePending(creatorId, pendingBefore, QuizGenerationStatus.PENDING,
                QuizGenerationStatus.FAILED, QuizGenerationFailure.STALE_PENDING, now);
        repository.expireRunning(creatorId, runningBefore, QuizGenerationStatus.RUNNING,
                QuizGenerationReviewStage.GROUNDING, QuizGenerationStatus.UNKNOWN, QuizGenerationFailure.STALE_RUNNING,
                now);
        repository.expireUnstartedGeneration(creatorId, pendingBefore, QuizGenerationStatus.RUNNING,
                QuizGenerationReviewStage.GENERATING, QuizGenerationStatus.FAILED, QuizGenerationFailure.STALE_PENDING,
                now);
        repository.expireStartedGeneration(creatorId, runningBefore, QuizGenerationStatus.RUNNING,
                QuizGenerationReviewStage.GENERATING, QuizGenerationStatus.UNKNOWN,
                QuizGenerationFailure.STALE_RUNNING, now);
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
     * RUNNING이고 원문 검토 단계가 {@code stage}일 때만 FAILED·UNKNOWN으로 끝낸다. {@code stage}가 null이면 기존 흐름과
     * 같다. Worker가 자기 단계가 아닌 작업(예: 검토 대기)을 끝내지 않도록 단계를 함께 건다.
     */
    @Transactional
    public boolean finishRunning(Long generationId, QuizGenerationReviewStage stage, QuizGenerationStatus status,
                                 QuizGenerationFailure failure, Instant now) {
        if (stage == null) {
            return finishRunning(generationId, status, failure, now);
        }
        return repository.finishInStage(generationId, QuizGenerationStatus.RUNNING, stage, status, failure, now) == 1;
    }

    /** Grounding 단계 작업에 결과를 저장하고 원문 검토 대기로 바꾼다. 그 사이 정리된 작업이면 저장하지 않는다. */
    @Transactional
    public boolean awaitReview(Long generationId, String contentText, String facts, String model,
                               String promptVersion, Instant now) {
        return repository.awaitReview(generationId, QuizGenerationStatus.RUNNING, QuizGenerationReviewStage.GROUNDING,
                QuizGenerationReviewStage.AWAITING_REVIEW, contentText, facts, model, promptVersion, now) == 1;
    }

    /**
     * 작업 행을 잠근 뒤 검토 대기면 원문을 확정해 생성 단계로 넘긴다. 확정 후에는 원문을 바꿀 수 없다. 같은 원문으로 이미
     * 확정했으면 REPLAYED, 다른 원문이면 {@code QUIZ_GENERATION_CONTENT_CONFLICT}, 그 밖의 상태(Grounding 중·취소·
     * 기존 흐름 등)는 {@code QUIZ_GENERATION_NOT_AWAITING_REVIEW}다.
     */
    @Transactional
    public Confirmation confirmContent(Long generationId, Long memberId, String contentText, Instant now) {
        QuizGeneration generation = lock(generationId);
        String contentTextSha256 = QuizRevisionFingerprint.contentTextSha256(contentText);
        if (generation.isAwaitingReview()) {
            generation.confirmContent(contentText, contentTextSha256, memberId, now);
            return Confirmation.CONFIRMED;
        }
        if (generation.getReviewedContentText() != null) {
            if (generation.getReviewedContentText().equals(contentText)) {
                return Confirmation.REPLAYED;
            }
            throw new BusinessException(QuizErrorCode.QUIZ_GENERATION_CONTENT_CONFLICT);
        }
        throw new BusinessException(QuizErrorCode.QUIZ_GENERATION_NOT_AWAITING_REVIEW);
    }

    /** 작업 행을 잠근 뒤 검토 대기면 FAILED(CANCELLED)로 끝낸다. 이미 취소했으면 REPLAYED다. */
    @Transactional
    public Cancellation cancelReview(Long generationId, Instant now) {
        QuizGeneration generation = lock(generationId);
        if (generation.isAwaitingReview()) {
            generation.cancelReview(now);
            return Cancellation.CANCELLED;
        }
        if (generation.isCancelled()) {
            return Cancellation.REPLAYED;
        }
        throw new BusinessException(QuizErrorCode.QUIZ_GENERATION_NOT_AWAITING_REVIEW);
    }

    /** 확정 원문으로 생성을 시작한다. 한 작업에서 한 번만 성공하며, 실패한 Worker는 Provider를 호출하지 않는다. */
    @Transactional
    public boolean claimGeneration(Long generationId, Instant now) {
        return repository.claimGeneration(generationId, QuizGenerationStatus.RUNNING,
                QuizGenerationReviewStage.GENERATING, now) == 1;
    }

    /** 아직 시작하지 않은 생성 단계 작업만 FAILED로 끝낸다(dispatch 거절 등). */
    @Transactional
    public boolean failUnstartedGeneration(Long generationId, QuizGenerationFailure failure, Instant now) {
        return repository.failUnstartedGeneration(generationId, QuizGenerationStatus.RUNNING,
                QuizGenerationReviewStage.GENERATING, QuizGenerationStatus.FAILED, failure, now) == 1;
    }

    /**
     * 작업 행을 잠근 뒤 아직 RUNNING일 때만 Quiz를 저장하고 SUCCEEDED로 바꾼다. 둘은 한 Transaction이라 함께
     * 커밋되거나 함께 롤백된다. 그 사이 UNKNOWN·FAILED로 정리된 작업은 저장하지 않고 빈 값을 돌려준다.
     *
     * <p>원문 검토 작업은 생성을 시작한 생성 단계일 때만 저장하고, 생성 입력이 확정 원문과 다르면(해시 불일치) 저장하지
     * 않고 예외를 던진다. 저장한 Quiz의 {@code content_text_sha256}은 확정 원문의 해시와 같다.
     */
    @Transactional
    public Optional<QuizDraft> completeRunning(Long generationId, QuizDraftCommand command, Instant now) {
        QuizGeneration generation = repository.findByIdForUpdate(generationId)
                .orElseThrow(() -> new IllegalStateException("Quiz 생성 작업이 없습니다. generationId=" + generationId));
        if (generation.getStatus() != QuizGenerationStatus.RUNNING) {
            return Optional.empty();
        }
        if (generation.getReviewStage() != null) {
            if (generation.getReviewStage() != QuizGenerationReviewStage.GENERATING
                    || generation.getGenerationStartedAt() == null) {
                return Optional.empty();
            }
            String inputSha256 = QuizRevisionFingerprint.contentTextSha256(command.input().contentText());
            if (!inputSha256.equals(generation.getReviewedContentSha256())) {
                throw new IllegalStateException("생성 입력이 확정 원문과 다릅니다. generationId=" + generationId);
            }
        }
        QuizDraft draft = draftService.create(command);
        generation.succeed(draft.quizId(), draft.revisionId(), now);
        return Optional.of(draft);
    }

    private QuizGeneration lock(Long generationId) {
        return repository.findByIdForUpdate(generationId)
                .orElseThrow(() -> new BusinessException(QuizErrorCode.QUIZ_GENERATION_NOT_FOUND));
    }
}
