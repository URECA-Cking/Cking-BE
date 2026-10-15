package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.quiz.application.dto.QuizDraftCommand;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizGeneration;
import kr.co.cking.quiz.domain.QuizGenerationFailure;
import kr.co.cking.quiz.domain.QuizGenerationReviewStage;
import kr.co.cking.quiz.domain.QuizGenerationStatus;
import kr.co.cking.quiz.generation.GeneratedQuiz;
import kr.co.cking.quiz.generation.QuizGenerationInput;
import kr.co.cking.quiz.generation.QuizGenerationValidationException;
import kr.co.cking.quiz.generation.QuizGenerator;
import kr.co.cking.quiz.generation.QuizInputException;
import kr.co.cking.quiz.generation.QuizProviderException;
import kr.co.cking.quiz.generation.QuizProviderTimeoutException;
import kr.co.cking.quiz.generation.QuizResponseParseException;
import kr.co.cking.quiz.grounding.GroundingFact;
import kr.co.cking.quiz.grounding.VideoGrounder;
import kr.co.cking.quiz.grounding.VideoGrounding;
import kr.co.cking.quiz.grounding.VideoGroundingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;

/**
 * Executor thread에서 Quiz 생성 작업 하나를 처리한다(이슈 #525).
 *
 * <p>작업을 PENDING → RUNNING으로 점유한 Worker만 진행한다. Grounding과 Quiz 생성 Provider 호출은 DB
 * Transaction 밖에서 하고, Quiz 저장과 SUCCEEDED 전환만 한 Transaction으로 묶는다. 시간 초과는 UNKNOWN, 그 밖의
 * 실패는 FAILED이며 어느 경우에도 Provider를 다시 호출하지 않는다. 로그에는 작업 ID와 실패 코드만 남긴다.
 *
 * <p>원문 검토 작업(이슈 #604)은 두 번 나눠 실행한다. {@link #run}은 Grounding만 하고 AI 원문과 facts를 저장한 뒤
 * 검토 대기로 끝낸다. Creator가 원문을 확정하면 {@link #runGeneration}이 생성 시작을 점유한 뒤 확정 원문으로만 Quiz를
 * 만든다. 단계별 실패는 자기 단계일 때만 기록해 검토 대기 작업을 끝내지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuizGenerationWorker {

    /** Grounding facts 저장 한도. 넘으면 계약 위반 응답으로 보고 원문을 저장하지 않는다. */
    static final int MAX_GROUNDING_FACTS = 200;
    static final int MAX_GROUNDING_FACTS_BYTES = 512 * 1024;

    private final QuizGenerationStore store;
    private final VideoGrounder videoGrounder;
    private final QuizGenerator quizGenerator;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    /**
     * 접수한 작업을 시작한다. 기존 작업은 Grounding부터 저장까지, 원문 검토 작업은 Grounding까지 한다.
     *
     * <p>실패 기록은 점유(PENDING → RUNNING)에 성공한 Worker만 한다. 점유 요청이 실패하거나 예외가 나면 다른 Worker가
     * 진행 중인 작업일 수 있으므로 상태를 바꾸지 않고 끝낸다. 점유되지 않은 작업은 오래된 작업 정리가 끝낸다.
     */
    public void run(Long generationId) {
        boolean claimed;
        try {
            claimed = store.claim(generationId, clock.instant());
        } catch (RuntimeException exception) {
            log.error("Quiz 생성 작업을 점유하지 못해 실행하지 않습니다. generationId={}, type={}",
                    generationId, exception.getClass().getSimpleName());
            return;
        }
        if (!claimed) {
            return;
        }
        QuizGenerationReviewStage stage = null;
        try {
            QuizGeneration generation = find(generationId);
            stage = generation.getReviewStage();
            if (stage == null) {
                process(generation);
            } else {
                ground(generation);
            }
        } catch (RuntimeException exception) {
            fail(generationId, stage, exception);
        }
    }

    /**
     * Creator가 확정한 원문으로 Quiz를 생성한다. 생성 시작을 점유하지 못하면 Provider를 호출하지 않는다.
     *
     * <p>실패 기록은 점유에 성공한 Worker만 한다. 점유 Transaction은 {@code true}를 돌려줄 때 이미 커밋돼 있다. 점유 요청이
     * 실패하거나 예외가 나면 다른 Worker가 진행 중인 작업일 수 있으므로 상태를 바꾸지 않고 끝낸다. 점유가 실제로 반영된
     * 채 예외가 났다면 생성 단계 정리 규칙(생성 시작 후 stale-running)이 끝낸다.
     */
    public void runGeneration(Long generationId) {
        boolean claimed;
        try {
            claimed = store.claimGeneration(generationId, clock.instant());
        } catch (RuntimeException exception) {
            log.error("생성 단계 시작을 점유하지 못해 실행하지 않습니다. generationId={}, type={}",
                    generationId, exception.getClass().getSimpleName());
            return;
        }
        if (!claimed) {
            log.info("이미 시작했거나 정리된 생성 단계라 실행하지 않습니다. generationId={}", generationId);
            return;
        }
        try {
            QuizGeneration generation = find(generationId);
            QuizGenerationInput input;
            GeneratedQuiz generated;
            try {
                input = new QuizGenerationInput(generation.getReviewedContentText(), generation.getQuestionCount(),
                        generation.getOptionCount());
                generated = quizGenerator.generate(input);
            } catch (QuizProviderTimeoutException exception) {
                finish(generationId, QuizGenerationReviewStage.GENERATING, QuizGenerationStatus.UNKNOWN,
                        QuizGenerationFailure.PROVIDER_TIMEOUT);
                return;
            } catch (QuizResponseParseException | QuizGenerationValidationException | QuizInputException exception) {
                finish(generationId, QuizGenerationReviewStage.GENERATING, QuizGenerationStatus.FAILED,
                        QuizGenerationFailure.GENERATION_INVALID);
                return;
            } catch (QuizProviderException exception) {
                finish(generationId, QuizGenerationReviewStage.GENERATING, QuizGenerationStatus.FAILED,
                        QuizGenerationFailure.PROVIDER_ERROR);
                return;
            }
            save(generation, input, generated);
        } catch (RuntimeException exception) {
            fail(generationId, QuizGenerationReviewStage.GENERATING, exception);
        }
    }

    private void process(QuizGeneration generation) {
        Long generationId = generation.getGenerationId();
        QuizGenerationInput input;
        GeneratedQuiz generated;
        try {
            VideoGrounding grounding = videoGrounder.ground(generation.getVideoId());
            input = new QuizGenerationInput(grounding.contentText(), generation.getQuestionCount(),
                    generation.getOptionCount());
            generated = quizGenerator.generate(input);
        } catch (QuizProviderTimeoutException exception) {
            finish(generationId, null, QuizGenerationStatus.UNKNOWN, QuizGenerationFailure.PROVIDER_TIMEOUT);
            return;
        } catch (VideoGroundingException exception) {
            finish(generationId, null, QuizGenerationStatus.FAILED, groundingFailure(exception));
            return;
        } catch (QuizResponseParseException | QuizGenerationValidationException | QuizInputException exception) {
            finish(generationId, null, QuizGenerationStatus.FAILED, QuizGenerationFailure.GENERATION_INVALID);
            return;
        } catch (QuizProviderException exception) {
            finish(generationId, null, QuizGenerationStatus.FAILED, QuizGenerationFailure.PROVIDER_ERROR);
            return;
        }
        save(generation, input, generated);
    }

    /** Grounding 결과를 저장하고 원문 검토 대기로 바꾼다. Quiz 생성 Provider는 호출하지 않는다. */
    private void ground(QuizGeneration generation) {
        Long generationId = generation.getGenerationId();
        VideoGrounding grounding;
        try {
            grounding = videoGrounder.ground(generation.getVideoId());
        } catch (QuizProviderTimeoutException exception) {
            finish(generationId, QuizGenerationReviewStage.GROUNDING, QuizGenerationStatus.UNKNOWN,
                    QuizGenerationFailure.PROVIDER_TIMEOUT);
            return;
        } catch (VideoGroundingException exception) {
            finish(generationId, QuizGenerationReviewStage.GROUNDING, QuizGenerationStatus.FAILED,
                    groundingFailure(exception));
            return;
        } catch (QuizProviderException exception) {
            finish(generationId, QuizGenerationReviewStage.GROUNDING, QuizGenerationStatus.FAILED,
                    QuizGenerationFailure.PROVIDER_ERROR);
            return;
        }
        String facts = factsJson(grounding.facts());
        if (facts == null) {
            finish(generationId, QuizGenerationReviewStage.GROUNDING, QuizGenerationStatus.FAILED,
                    QuizGenerationFailure.GROUNDING_INVALID);
            return;
        }
        if (!store.awaitReview(generationId, grounding.contentText(), facts, grounding.model(),
                grounding.promptVersion(), clock.instant())) {
            log.info("이미 정리된 Quiz 생성 작업이라 Grounding 결과를 저장하지 않습니다. generationId={}", generationId);
        }
    }

    /** facts JSON 배열. 개수나 직렬화 크기가 한도를 넘으면 null이다. */
    private String factsJson(List<GroundingFact> facts) {
        if (facts.size() > MAX_GROUNDING_FACTS) {
            return null;
        }
        String json = objectMapper.writeValueAsString(facts);
        return json.getBytes(StandardCharsets.UTF_8).length > MAX_GROUNDING_FACTS_BYTES ? null : json;
    }

    private void save(QuizGeneration generation, QuizGenerationInput input, GeneratedQuiz generated) {
        Long generationId = generation.getGenerationId();
        QuizGenerationReviewStage stage = generation.getReviewStage();
        QuizDraftCommand command = new QuizDraftCommand(generation.getCreatorId(), generation.getVideoId(),
                generation.getTitle(), input, generated);
        try {
            if (store.completeRunning(generationId, command, clock.instant()).isEmpty()) {
                log.info("이미 정리된 Quiz 생성 작업이라 결과를 저장하지 않습니다. generationId={}", generationId);
            }
        } catch (BusinessException exception) {
            if (exception.getErrorCode() != QuizErrorCode.QUIZ_DRAFT_INVALID) {
                throw exception;
            }
            finish(generationId, stage, QuizGenerationStatus.FAILED, QuizGenerationFailure.DRAFT_INVALID);
        } catch (QuizGenerationValidationException | QuizInputException exception) {
            finish(generationId, stage, QuizGenerationStatus.FAILED, QuizGenerationFailure.DRAFT_INVALID);
        }
    }

    private QuizGeneration find(Long generationId) {
        return store.find(generationId)
                .orElseThrow(() -> new IllegalStateException("Quiz 생성 작업이 없습니다. generationId=" + generationId));
    }

    private QuizGenerationFailure groundingFailure(VideoGroundingException exception) {
        return switch (exception.getFailure()) {
            case REJECTED -> QuizGenerationFailure.GROUNDING_REJECTED;
            case INVALID_RESPONSE -> QuizGenerationFailure.GROUNDING_INVALID;
            case NOT_CONFIGURED, PROVIDER_ERROR -> QuizGenerationFailure.PROVIDER_ERROR;
        };
    }

    private void fail(Long generationId, QuizGenerationReviewStage stage, RuntimeException exception) {
        log.error("Quiz 생성 작업 처리 중 예상하지 못한 오류가 났습니다. generationId={}, type={}",
                generationId, exception.getClass().getSimpleName());
        finishRunning(generationId, stage, QuizGenerationStatus.FAILED, QuizGenerationFailure.INTERNAL_ERROR);
    }

    private void finish(Long generationId, QuizGenerationReviewStage stage, QuizGenerationStatus status,
                        QuizGenerationFailure failure) {
        if (finishRunning(generationId, stage, status, failure)) {
            log.warn("Quiz 생성 작업이 끝나지 못했습니다. generationId={}, status={}, failure={}",
                    generationId, status, failure);
        }
    }

    /** 기존 흐름은 단계 조건 없이, 원문 검토 작업은 자기 단계일 때만 끝낸다. */
    private boolean finishRunning(Long generationId, QuizGenerationReviewStage stage, QuizGenerationStatus status,
                                  QuizGenerationFailure failure) {
        return stage == null
                ? store.finishRunning(generationId, status, failure, clock.instant())
                : store.finishRunning(generationId, stage, status, failure, clock.instant());
    }
}
