package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.quiz.domain.Quiz;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizJudgeJob;
import kr.co.cking.quiz.domain.QuizJudgeJobStatus;
import kr.co.cking.quiz.domain.QuizJudgeSeries;
import kr.co.cking.quiz.domain.QuizJudgeStoredResult;
import kr.co.cking.quiz.domain.QuizJudgeStoredResultItem;
import kr.co.cking.quiz.domain.QuizRevision;
import kr.co.cking.quiz.judge.JudgeRubric;
import kr.co.cking.quiz.judge.QuizJudgeEvaluation;
import kr.co.cking.quiz.judge.QuizJudgeEvaluationValidator;
import kr.co.cking.quiz.judge.QuizJudgeException;
import kr.co.cking.quiz.judge.QuizJudgeFingerprint;
import kr.co.cking.quiz.judge.QuizJudgeInput;
import kr.co.cking.quiz.repository.QuizRepository;
import kr.co.cking.quiz.repository.QuizRevisionRepository;
import kr.co.cking.quiz.repository.QuizJudgeSeriesRepository;
import kr.co.cking.quiz.repository.QuizJudgeJobRepository;
import kr.co.cking.quiz.repository.QuizJudgeStoredResultItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 저장된 평가의 소속·입력·설정·판정 해시를 재검증한다. 현재 Provider 설정은 사용하지 않는다. */
@Component
@RequiredArgsConstructor
public class QuizJudgeResultReader {
    private final QuizRepository quizRepository;
    private final QuizRevisionRepository revisionRepository;
    private final QuizRevisionReader revisionReader;
    private final QuizJudgeSeriesRepository seriesRepository;
    private final QuizJudgeJobRepository jobRepository;
    private final QuizJudgeStoredResultItemRepository itemRepository;

    @Transactional(readOnly = true)
    public Verified read(QuizJudgeStoredResult result) {
        QuizJudgeJob job = jobRepository.findById(result.getJobId()).orElseThrow(this::invalid);
        QuizJudgeSeries series = seriesRepository.findById(result.getSeriesId()).orElseThrow(this::invalid);
        Quiz quiz = quizRepository.findById(job.getQuizId()).orElseThrow(this::invalid);
        QuizRevision revision = revisionRepository.findById(result.getRevisionId()).orElseThrow(this::invalid);
        if (job.getStatus() != QuizJudgeJobStatus.SUCCEEDED
                || !job.getSeriesId().equals(series.getSeriesId())
                || !job.getRevisionId().equals(result.getRevisionId())
                || !series.getRevisionId().equals(revision.getRevisionId())
                || !series.getQuizId().equals(quiz.getQuizId())
                || !revision.getQuizId().equals(quiz.getQuizId())
                || !job.getCreatorId().equals(quiz.getCreatorId())
                || result.getQuestionCount() != revision.getQuestionCount()) {
            throw invalid();
        }
        var contents = revisionReader.readVerified(quiz, revision);
        String inputSha;
        try {
            List<QuizJudgeInput.Question> questions = contents.stream()
                    .map(q -> new QuizJudgeInput.Question(q.questionNo(), q.question(), q.options(), q.correctOptionIndex(),
                            q.explanation(), q.sourceEvidence())).toList();
            inputSha = QuizJudgeFingerprint.inputSha256(new QuizJudgeInput(quiz.getContentText(), questions));
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
        if (!quiz.getContentTextSha256().equals(series.getContentTextSha256())
                || !revision.getRevisionSha256().equals(series.getRevisionSha256())
                || !inputSha.equals(series.getInputSha256()) || !inputSha.equals(result.getInputSha256())
                || !QuizJudgeFingerprint.settingsFingerprint(series.settings()).equals(series.getSettingsFingerprint())
                || !series.getSettingsFingerprint().equals(result.getSettingsFingerprint())
                || !series.getPolicyVersion().equals(result.getPolicyVersion())) {
            throw invalid();
        }
        // 저장된 v1 평가에는 운영 설정 변경 이후에도 v1 집계 규칙을 적용한다.
        if (!"quiz-judge-policy-v1".equals(result.getPolicyVersion())) {
            throw invalid();
        }
        QuizJudgeEvaluation evaluation;
        try {
            Map<Integer, Map<JudgeRubric, QuizJudgeEvaluation.RubricResult>> grouped = new TreeMap<>();
            for (QuizJudgeStoredResultItem item : itemRepository
                    .findByResultIdOrderByQuestionNoAscItemIdAsc(result.getResultId())) {
                if (!result.getResultId().equals(item.getResultId()) || item.getRubric() == null
                        || grouped.computeIfAbsent(item.getQuestionNo(), ignored -> new EnumMap<>(JudgeRubric.class))
                        .putIfAbsent(item.getRubric(), new QuizJudgeEvaluation.RubricResult(item.getVerdict(), item.getReason()))
                        != null) {
                    throw invalid();
                }
            }
            // Validator는 snapshot의 reason 한도와 별개로 policy v1의 절대 200자 상한도 검사한다.
            evaluation = new QuizJudgeEvaluationValidator().validate(grouped.entrySet().stream()
                    .map(entry -> new QuizJudgeEvaluation.QuestionEvaluation(entry.getKey(), entry.getValue())).toList(),
                    revision.getQuestionCount(), series.getReasonMaxLength());
        } catch (IllegalArgumentException | QuizJudgeException exception) {
            throw invalid();
        }
        if (evaluation.aggregation() != result.getAggregation()
                || !QuizJudgeFingerprint.resultSha256(inputSha, series.getSettingsFingerprint(),
                result.getPolicyVersion(), evaluation).equals(result.getResultSha256())) {
            throw invalid();
        }
        return new Verified(result, new QuizApprovalPolicy.Target(quiz.getQuizId(), revision.getRevisionId(),
                quiz.getContentTextSha256(), revision.getRevisionSha256()), evaluation);
    }

    private BusinessException invalid() {
        return new BusinessException(QuizErrorCode.QUIZ_JUDGE_EVALUATION_INTEGRITY_FAILED);
    }

    public record Verified(QuizJudgeStoredResult result, QuizApprovalPolicy.Target target, QuizJudgeEvaluation evaluation) {}
}
