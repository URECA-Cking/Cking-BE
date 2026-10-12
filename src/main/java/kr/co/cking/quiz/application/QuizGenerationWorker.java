package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.quiz.application.dto.QuizDraftCommand;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizGeneration;
import kr.co.cking.quiz.domain.QuizGenerationFailure;
import kr.co.cking.quiz.domain.QuizGenerationStatus;
import kr.co.cking.quiz.generation.GeneratedQuiz;
import kr.co.cking.quiz.generation.QuizGenerationInput;
import kr.co.cking.quiz.generation.QuizGenerationValidationException;
import kr.co.cking.quiz.generation.QuizGenerator;
import kr.co.cking.quiz.generation.QuizInputException;
import kr.co.cking.quiz.generation.QuizProviderException;
import kr.co.cking.quiz.generation.QuizProviderTimeoutException;
import kr.co.cking.quiz.generation.QuizResponseParseException;
import kr.co.cking.quiz.grounding.VideoGrounder;
import kr.co.cking.quiz.grounding.VideoGrounding;
import kr.co.cking.quiz.grounding.VideoGroundingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Executor thread에서 Quiz 생성 작업 하나를 처리한다(이슈 #525).
 *
 * <p>작업을 PENDING → RUNNING으로 점유한 Worker만 진행한다. Grounding과 Quiz 생성 Provider 호출은 DB
 * Transaction 밖에서 하고, Quiz 저장과 SUCCEEDED 전환만 한 Transaction으로 묶는다. 시간 초과는 UNKNOWN, 그 밖의
 * 실패는 FAILED이며 어느 경우에도 Provider를 다시 호출하지 않는다. 로그에는 작업 ID와 실패 코드만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuizGenerationWorker {

    private final QuizGenerationStore store;
    private final VideoGrounder videoGrounder;
    private final QuizGenerator quizGenerator;
    private final Clock clock;

    public void run(Long generationId) {
        try {
            process(generationId);
        } catch (RuntimeException exception) {
            log.error("Quiz 생성 작업 처리 중 예상하지 못한 오류가 났습니다. generationId={}, type={}",
                    generationId, exception.getClass().getSimpleName());
            store.finishRunning(generationId, QuizGenerationStatus.FAILED, QuizGenerationFailure.INTERNAL_ERROR,
                    clock.instant());
        }
    }

    private void process(Long generationId) {
        if (!store.claim(generationId, clock.instant())) {
            return;
        }
        QuizGeneration generation = store.find(generationId)
                .orElseThrow(() -> new IllegalStateException("Quiz 생성 작업이 없습니다. generationId=" + generationId));
        QuizGenerationInput input;
        GeneratedQuiz generated;
        try {
            VideoGrounding grounding = videoGrounder.ground(generation.getVideoId());
            input = new QuizGenerationInput(grounding.contentText(), generation.getQuestionCount(),
                    generation.getOptionCount());
            generated = quizGenerator.generate(input);
        } catch (QuizProviderTimeoutException exception) {
            finish(generationId, QuizGenerationStatus.UNKNOWN, QuizGenerationFailure.PROVIDER_TIMEOUT);
            return;
        } catch (VideoGroundingException exception) {
            finish(generationId, QuizGenerationStatus.FAILED, groundingFailure(exception));
            return;
        } catch (QuizResponseParseException | QuizGenerationValidationException | QuizInputException exception) {
            finish(generationId, QuizGenerationStatus.FAILED, QuizGenerationFailure.GENERATION_INVALID);
            return;
        } catch (QuizProviderException exception) {
            finish(generationId, QuizGenerationStatus.FAILED, QuizGenerationFailure.PROVIDER_ERROR);
            return;
        }
        save(generation, input, generated);
    }

    private void save(QuizGeneration generation, QuizGenerationInput input, GeneratedQuiz generated) {
        Long generationId = generation.getGenerationId();
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
            finish(generationId, QuizGenerationStatus.FAILED, QuizGenerationFailure.DRAFT_INVALID);
        } catch (QuizGenerationValidationException | QuizInputException exception) {
            finish(generationId, QuizGenerationStatus.FAILED, QuizGenerationFailure.DRAFT_INVALID);
        }
    }

    private QuizGenerationFailure groundingFailure(VideoGroundingException exception) {
        return switch (exception.getFailure()) {
            case REJECTED -> QuizGenerationFailure.GROUNDING_REJECTED;
            case INVALID_RESPONSE -> QuizGenerationFailure.GROUNDING_INVALID;
            case NOT_CONFIGURED, PROVIDER_ERROR -> QuizGenerationFailure.PROVIDER_ERROR;
        };
    }

    private void finish(Long generationId, QuizGenerationStatus status, QuizGenerationFailure failure) {
        if (store.finishRunning(generationId, status, failure, clock.instant())) {
            log.warn("Quiz 생성 작업이 끝나지 못했습니다. generationId={}, status={}, failure={}",
                    generationId, status, failure);
        }
    }
}
