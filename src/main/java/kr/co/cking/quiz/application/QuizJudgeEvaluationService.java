package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.quiz.application.config.QuizJudgeEvaluationProperties;
import kr.co.cking.quiz.application.dto.QuizJudgeEvaluationView;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizJudgeJob;
import kr.co.cking.quiz.judge.QuizJudge;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Creator의 Quiz revision Judge 평가 요청 접수와 상태 조회(이슈 #529). Provider를 호출하지 않는다.
 *
 * <p>이 Service는 Transaction을 열지 않는다. 각 DB 작업은 {@link QuizJudgeEvaluationStore}의 짧은 Transaction이며, 새
 * Job은 접수가 커밋된 뒤 {@link QuizJudgeDispatcher}로 제출한다. 같은 requestId 재요청은 제출하지 않는다.
 *
 * <ul>
 *   <li>같은 requestId·같은 Quiz·revision 재요청은 오래된 Job을 정리한 뒤 기존 Job의 최신 상태를 돌려준다. 대상이
 *       다르면 409다. 기능이 꺼져 있거나 Provider 설정이 없어도 재요청과 조회는 된다</li>
 *   <li>기능이 꺼져 있거나 Provider 설정이 없으면 새 Job을 만들기 전에 503으로 막아 시도 횟수를 쓰지 않는다</li>
 *   <li>새 요청은 오래된 Job을 정리한 뒤 접수한다. 접수 중 Quiz 잠금을 잡은 뒤 같은 requestId가 이미 접수돼 있으면
 *       Store가 그 Job을 돌려주고({@code created=false}) 재요청으로 다룬다. 다른 접수와 겹쳐 고유 제약에 걸리면 새
 *       Transaction에서 다시 읽어, 같은 requestId면 재요청으로, 아니면 진행 중 409로 처리한다</li>
 *   <li>실행 설정이 첫 요청 때와 달라졌으면 새 시도는 409다. 재요청과 조회는 설정과 관계없이 된다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class QuizJudgeEvaluationService {

    private static final int MAX_ADMISSION_ATTEMPTS = 3;

    private final QuizCreatorLookup creatorLookup;
    private final QuizJudgeEvaluationStore store;
    private final QuizJudgeDispatcher dispatcher;
    private final QuizJudgeResultReader resultReader;
    private final QuizJudge judge;
    private final QuizJudgeEvaluationProperties properties;
    private final Clock clock;

    public QuizJudgeEvaluationView request(Long memberId, Long quizId, Long revisionId, UUID requestId) {
        Creator creator = creatorLookup.requireCreator(memberId);
        if (requestId == null) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        Long creatorId = creator.getCreatorId();
        store.requireTarget(creatorId, quizId, revisionId);
        String request = requestId.toString();
        Optional<QuizJudgeJob> existing = store.findByRequest(creatorId, request);
        if (existing.isPresent()) {
            return replay(creatorId, existing.get(), quizId, revisionId);
        }
        if (!properties.isEnabled()) {
            throw new BusinessException(QuizErrorCode.QUIZ_JUDGE_DISABLED);
        }
        if (!judge.isConfigured()) {
            throw new BusinessException(QuizErrorCode.QUIZ_JUDGE_NOT_CONFIGURED);
        }
        Instant now = clock.instant();
        store.expireStale(creatorId, now);
        QuizJudgeEvaluationStore.Admission admission = null;
        for (int attempt = 1; attempt <= MAX_ADMISSION_ATTEMPTS; attempt++) {
            try {
                admission = store.create(creatorId, memberId, quizId, revisionId, request, clock.instant());
                break;
            } catch (CannotAcquireLockException exception) {
                // Store 프록시의 Transaction이 롤백된 뒤 새 Transaction으로 다시 조회한다.
                Optional<QuizJudgeJob> concurrent = store.findByRequest(creatorId, request);
                if (concurrent.isPresent()) {
                    return replay(creatorId, concurrent.get(), quizId, revisionId);
                }
                if (attempt == MAX_ADMISSION_ATTEMPTS) {
                    // 활성 Job의 존재를 확인하지 못했으므로 진행 중 409로 추정하지 않는다.
                    throw new BusinessException(CommonErrorCode.SYSTEM_ERROR);
                }
            } catch (DataIntegrityViolationException exception) {
                return store.findByRequest(creatorId, request)
                        .map(job -> replay(creatorId, job, quizId, revisionId))
                        .orElseThrow(() -> new BusinessException(QuizErrorCode.QUIZ_JUDGE_IN_PROGRESS));
            }
        }
        if (!admission.created()) {
            // 처음 조회 뒤 같은 requestId가 먼저 접수됐다. 재요청이므로 실행을 다시 제출하지 않는다.
            return refreshed(creatorId, admission.job());
        }
        Long jobId = admission.job().getJobId();
        dispatcher.dispatch(jobId);
        // 제출이 거절되면 Dispatcher가 FAILED로 끝내므로 다시 읽어 돌려준다.
        return view(store.findJob(jobId).orElse(admission.job()));
    }

    /** 경로의 Quiz·revision에 속하지 않거나 없는 Job은 404, 다른 Creator의 Job은 403. 조회하면서 오래된 Job을 정리한다. */
    public QuizJudgeEvaluationView find(Long memberId, Long quizId, Long revisionId, Long evaluationId) {
        Creator creator = creatorLookup.requireCreator(memberId);
        store.requireTarget(creator.getCreatorId(), quizId, revisionId);
        QuizJudgeJob job = store.findJob(evaluationId)
                .filter(found -> found.targets(quizId, revisionId))
                .orElseThrow(() -> new BusinessException(QuizErrorCode.QUIZ_JUDGE_EVALUATION_NOT_FOUND));
        if (!job.isOwnedBy(creator.getCreatorId())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return refreshed(creator.getCreatorId(), job);
    }

    private QuizJudgeEvaluationView replay(Long creatorId, QuizJudgeJob job, Long quizId, Long revisionId) {
        if (!job.targets(quizId, revisionId)) {
            throw new BusinessException(QuizErrorCode.QUIZ_JUDGE_REQUEST_CONFLICT);
        }
        return refreshed(creatorId, job);
    }

    /** 오래된 Job을 정리한 뒤 다시 읽은 최신 상태. 새 Job을 만들거나 실행을 제출하지 않는다. */
    private QuizJudgeEvaluationView refreshed(Long creatorId, QuizJudgeJob job) {
        store.expireStale(creatorId, clock.instant());
        return view(store.findJob(job.getJobId()).orElse(job));
    }

    private QuizJudgeEvaluationView view(QuizJudgeJob job) {
        var result = store.findResult(job.getJobId()).orElse(null);
        if (result != null && job.getStatus() != kr.co.cking.quiz.domain.QuizJudgeJobStatus.SUCCEEDED) {
            // 결과는 SUCCEEDED 변경과 함께 커밋된다. 앞선 Job 조회 이후 완료됐다면 상태를 다시 읽는다.
            job = store.findJob(job.getJobId()).orElseThrow(() ->
                    new BusinessException(QuizErrorCode.QUIZ_JUDGE_EVALUATION_INTEGRITY_FAILED));
        }
        if (job.getStatus() != kr.co.cking.quiz.domain.QuizJudgeJobStatus.SUCCEEDED) {
            if (result != null) {
                throw new BusinessException(QuizErrorCode.QUIZ_JUDGE_EVALUATION_INTEGRITY_FAILED);
            }
            return QuizJudgeEvaluationView.of(job, null);
        }
        if (result == null) {
            throw new BusinessException(QuizErrorCode.QUIZ_JUDGE_EVALUATION_INTEGRITY_FAILED);
        }
        if (!job.getJobId().equals(result.getJobId()) || !job.getRevisionId().equals(result.getRevisionId())) {
            throw new BusinessException(QuizErrorCode.QUIZ_JUDGE_EVALUATION_INTEGRITY_FAILED);
        }
        return QuizJudgeEvaluationView.of(job, result, resultReader.read(result).evaluation().questions());
    }
}
