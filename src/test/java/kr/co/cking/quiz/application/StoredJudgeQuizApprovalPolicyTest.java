package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.quiz.QuizFixtures;
import kr.co.cking.quiz.application.dto.QuizQuestionContent;
import kr.co.cking.quiz.domain.*;
import kr.co.cking.quiz.judge.*;
import kr.co.cking.quiz.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

class StoredJudgeQuizApprovalPolicyTest {
    private final QuizRepository quizzes = mock(QuizRepository.class);
    private final QuizRevisionRepository revisions = mock(QuizRevisionRepository.class);
    private final QuizRevisionReader revisionReader = mock(QuizRevisionReader.class);
    private final QuizJudgeSeriesRepository seriesRepo = mock(QuizJudgeSeriesRepository.class);
    private final QuizJudgeJobRepository jobs = mock(QuizJudgeJobRepository.class);
    private final QuizJudgeStoredResultRepository results = mock(QuizJudgeStoredResultRepository.class);
    private final QuizJudgeStoredResultItemRepository items = mock(QuizJudgeStoredResultItemRepository.class);
    private final QuizJudgeResultReader reader = new QuizJudgeResultReader(quizzes, revisions, revisionReader,
            seriesRepo, jobs, items);
    private final StoredJudgeQuizApprovalPolicy policy = new StoredJudgeQuizApprovalPolicy(results, reader);
    private final Quiz quiz = QuizFixtures.quiz(1L, 10L);
    private final QuizRevision revision = QuizFixtures.revision(100L, 1L, 1);
    private final QuizApprovalPolicy.Target target = new QuizApprovalPolicy.Target(1L, 100L,
            quiz.getContentTextSha256(), revision.getRevisionSha256());
    private QuizJudgeSeries series;
    private QuizJudgeJob job;
    private QuizJudgeStoredResult result;
    private List<QuizJudgeStoredResultItem> rows;

    @BeforeEach
    void setUp() {
        given(quizzes.findById(1L)).willReturn(Optional.of(quiz));
        given(revisions.findById(100L)).willReturn(Optional.of(revision));
        List<QuizQuestionContent> contents = new ArrayList<>();
        List<QuizJudgeInput.Question> questions = new ArrayList<>();
        ReflectionTestUtils.setField(revision, "questionCount", 3);
        ReflectionTestUtils.setField(revision, "optionCount", 4);
        for (int no = 0; no < 3; no++) {
            var q = QuizJudgeFixtures.input(3).questions().get(no);
            contents.add(new QuizQuestionContent(no, q.question(), q.options(), q.correctOptionIndex(),
                    q.explanation(), q.sourceEvidence()));
            questions.add(new QuizJudgeInput.Question(no, q.question(), q.options(), q.correctOptionIndex(),
                    q.explanation(), q.sourceEvidence()));
        }
        given(revisionReader.readVerified(quiz, revision)).willReturn(contents);
        var input = new QuizJudgeInput(quiz.getContentText(), questions);
        var settings = QuizJudgeSettings.current("historical-model", "medium", 8192, 200, new ObjectMapper());
        series = new QuizJudgeSeries(1L, 100L, quiz.getContentTextSha256(), revision.getRevisionSha256(),
                QuizJudgeFingerprint.inputSha256(input), settings, QuizFixtures.NOW);
        ReflectionTestUtils.setField(series, "seriesId", 30L);
        given(seriesRepo.findById(30L)).willReturn(Optional.of(series));
        job = new QuizJudgeJob(series, 10L, 7L, UUID.randomUUID().toString(), 1, QuizFixtures.NOW);
        ReflectionTestUtils.setField(job, "jobId", 40L);
        ReflectionTestUtils.setField(job, "status", QuizJudgeJobStatus.SUCCEEDED);
        given(jobs.findById(40L)).willReturn(Optional.of(job));
        setVerdict(JudgeVerdict.PASS);
    }

    private void setVerdict(JudgeVerdict verdict) {
        var evaluation = new QuizJudgeEvaluationValidator().validate(new QuizJudgeResponseParser(new ObjectMapper())
                .parse(QuizJudgeFixtures.output(3, (no, rubric) -> new String[]{verdict.value(), "근거"})), 3, 200);
        var value = new QuizJudgeResult(evaluation, series.settings(), series.getInputSha256(),
                series.getSettingsFingerprint(), QuizJudgeFingerprint.resultSha256(series.getInputSha256(),
                series.getSettingsFingerprint(), series.getPolicyVersion(), evaluation), null);
        result = new QuizJudgeStoredResult(job, value, QuizFixtures.NOW);
        ReflectionTestUtils.setField(result, "resultId", 50L);
        rows = new ArrayList<>();
        for (var question : evaluation.questions()) {
            for (var rubric : JudgeRubric.values()) {
                var item = question.item(rubric);
                rows.add(new QuizJudgeStoredResultItem(50L, question.questionNo(), rubric, item.verdict(), item.reason()));
            }
        }
        given(results.findByRevisionId(100L)).willReturn(Optional.of(result));
        given(results.findById(50L)).willReturn(Optional.of(result));
        given(items.findByResultIdOrderByQuestionNoAscItemIdAsc(50L)).willReturn(rows);
    }

    @Test void ALL_PASS만_승인하며_고정한_결과_ID와_hash를_반환한다() {
        assertThat(policy.requireApprovable(target)).isEqualTo(new QuizApprovalPolicy.Basis(50L, result.getResultSha256()));
        verify(quizzes, never()).save(any());
    }
    @Test void fail_uncertain은_유효한_평가지만_승인은_거부한다() {
        for (var verdict : List.of(JudgeVerdict.FAIL, JudgeVerdict.UNCERTAIN)) {
            setVerdict(verdict);
            error(() -> policy.requireApprovable(target), QuizErrorCode.QUIZ_JUDGE_EVALUATION_REQUIRED);
        }
    }
    @Test void 평가가_없거나_진행중_실패_UNKNOWN이면_승인하지_않는다() {
        given(results.findByRevisionId(100L)).willReturn(Optional.empty());
        error(() -> policy.requireApprovable(target), QuizErrorCode.QUIZ_JUDGE_EVALUATION_REQUIRED);
        given(results.findByRevisionId(100L)).willReturn(Optional.of(result));
        for (var status : List.of(QuizJudgeJobStatus.PENDING, QuizJudgeJobStatus.RUNNING,
                QuizJudgeJobStatus.FAILED, QuizJudgeJobStatus.UNKNOWN)) {
            ReflectionTestUtils.setField(job, "status", status);
            error(() -> policy.requireApprovable(target), QuizErrorCode.QUIZ_JUDGE_EVALUATION_INTEGRITY_FAILED);
        }
    }
    @Test void 저장된_result_hash나_판정이_변조되면_차단한다() {
        ReflectionTestUtils.setField(result, "resultSha256", "a".repeat(64));
        error(() -> policy.requireApprovable(target), QuizErrorCode.QUIZ_JUDGE_EVALUATION_INTEGRITY_FAILED);
    }
    @Test void 문항_항목_누락과_추가는_차단한다() {
        rows.remove(0);
        error(() -> policy.requireApprovable(target), QuizErrorCode.QUIZ_JUDGE_EVALUATION_INTEGRITY_FAILED);
        setVerdict(JudgeVerdict.PASS);
        rows.add(rows.get(0));
        error(() -> policy.requireApprovable(target), QuizErrorCode.QUIZ_JUDGE_EVALUATION_INTEGRITY_FAILED);
    }
    @Test void 저장된_reason과_설정_snapshot_변조를_검출한다() {
        ReflectionTestUtils.setField(rows.get(0), "reason", "바뀐 근거");
        error(() -> policy.requireApprovable(target), QuizErrorCode.QUIZ_JUDGE_EVALUATION_INTEGRITY_FAILED);
        setUp();
        ReflectionTestUtils.setField(series, "model", "tampered-model");
        error(() -> policy.requireApprovable(target), QuizErrorCode.QUIZ_JUDGE_EVALUATION_INTEGRITY_FAILED);
    }
    @Test void 다른_revision이나_입력_hash_변조는_차단한다() {
        ReflectionTestUtils.setField(series, "inputSha256", "f".repeat(64));
        error(() -> policy.requireApprovable(target), QuizErrorCode.QUIZ_JUDGE_EVALUATION_INTEGRITY_FAILED);
        setUp();
        ReflectionTestUtils.setField(result, "revisionId", 999L);
        error(() -> policy.requireApprovable(target), QuizErrorCode.QUIZ_JUDGE_EVALUATION_INTEGRITY_FAILED);
    }
    @Test void revision_무결성_검사를_다시_수행한다() {
        given(revisionReader.readVerified(quiz, revision))
                .willThrow(new BusinessException(QuizErrorCode.QUIZ_REVISION_INTEGRITY_FAILED));
        error(() -> policy.requireApprovable(target), QuizErrorCode.QUIZ_REVISION_INTEGRITY_FAILED);
    }
    @Test void 미지원_policyVersion_자체로_승인을_차단한다() {
        ReflectionTestUtils.setField(series, "policyVersion", "unsupported-policy-v2");
        ReflectionTestUtils.setField(series, "settingsFingerprint", QuizJudgeFingerprint.settingsFingerprint(series.settings()));
        setVerdict(JudgeVerdict.PASS);
        assertThat(result.getPolicyVersion()).isEqualTo(series.getPolicyVersion());
        assertThat(result.getSettingsFingerprint()).isEqualTo(QuizJudgeFingerprint.settingsFingerprint(series.settings()));
        error(() -> policy.requireApprovable(target), QuizErrorCode.QUIZ_JUDGE_EVALUATION_INTEGRITY_FAILED);
        verify(items, never()).findByResultIdOrderByQuestionNoAscItemIdAsc(any());
    }
    @Test void aggregate_변조는_차단한다() {
        ReflectionTestUtils.setField(result, "aggregation", JudgeAggregation.HAS_FAIL);
        error(() -> policy.requireApprovable(target), QuizErrorCode.QUIZ_JUDGE_EVALUATION_INTEGRITY_FAILED);
    }
    @Test void 게시에는_승인시_고정한_ID와_hash를_사용하며_최신_평가를_찾지_않는다() {
        policy.requireStillApproved(target, QuizRevisionDecision.approve(100L, 7L, 50L,
                result.getResultSha256(), target.revisionSha256(), QuizFixtures.NOW));
        verify(results, never()).findByRevisionId(any());
    }
    @Test void Creator_책임_승인이나_승인시와_다른_revision_해시는_기존_Judge_정책으로_게시할_수_없다() {
        error(() -> policy.requireStillApproved(target, QuizRevisionDecision.approveByCreatorReview(100L, 7L,
                target.revisionSha256(), QuizFixtures.NOW)), QuizErrorCode.QUIZ_JUDGE_EVALUATION_INTEGRITY_FAILED);
        error(() -> policy.requireStillApproved(target, QuizRevisionDecision.approve(100L, 7L, 50L,
                result.getResultSha256(), "f".repeat(64), QuizFixtures.NOW)),
                QuizErrorCode.QUIZ_JUDGE_EVALUATION_INTEGRITY_FAILED);
        verify(results, never()).findById(any());
    }
    @Test void 승인_근거_ID_hash_revision이_바뀌면_게시를_차단한다() {
        error(() -> policy.requireStillApproved(target, QuizRevisionDecision.approve(100L, 7L, 50L,
                "f".repeat(64), target.revisionSha256(), QuizFixtures.NOW)), QuizErrorCode.QUIZ_JUDGE_EVALUATION_INTEGRITY_FAILED);
        error(() -> policy.requireStillApproved(target, QuizRevisionDecision.approve(999L, 7L, 50L,
                result.getResultSha256(), target.revisionSha256(), QuizFixtures.NOW)), QuizErrorCode.QUIZ_JUDGE_EVALUATION_INTEGRITY_FAILED);
    }
    @Test void 승인_result_ID가_다른_revision_결과나_없는_ID로_바뀌면_게시를_차단한다() {
        String approvedSha = result.getResultSha256();
        // 같은 입력·설정·판정이라 SHA가 같아도 다른 revision의 결과를 승인 근거로 쓰지 않는다.
        QuizRevision otherRevision = QuizFixtures.revision(101L, 1L, 2);
        ReflectionTestUtils.setField(otherRevision, "questionCount", 3);
        ReflectionTestUtils.setField(otherRevision, "optionCount", 4);
        ReflectionTestUtils.setField(otherRevision, "revisionSha256", revision.getRevisionSha256());
        given(revisions.findById(101L)).willReturn(Optional.of(otherRevision));
        var otherSeries = new QuizJudgeSeries(1L, 101L, series.getContentTextSha256(), series.getRevisionSha256(),
                series.getInputSha256(), series.settings(), QuizFixtures.NOW);
        ReflectionTestUtils.setField(otherSeries, "seriesId", 31L);
        given(seriesRepo.findById(31L)).willReturn(Optional.of(otherSeries));
        var otherJob = new QuizJudgeJob(otherSeries, 10L, 7L, UUID.randomUUID().toString(), 1, QuizFixtures.NOW);
        ReflectionTestUtils.setField(otherJob, "jobId", 41L);
        ReflectionTestUtils.setField(otherJob, "status", QuizJudgeJobStatus.SUCCEEDED);
        given(jobs.findById(41L)).willReturn(Optional.of(otherJob));
        var sameContents = revisionReader.readVerified(quiz, revision);
        given(revisionReader.readVerified(quiz, otherRevision)).willReturn(sameContents);
        var evaluation = reader.read(result).evaluation();
        var other = new QuizJudgeStoredResult(otherJob, new QuizJudgeResult(evaluation, otherSeries.settings(),
                otherSeries.getInputSha256(), otherSeries.getSettingsFingerprint(), approvedSha, null), QuizFixtures.NOW);
        ReflectionTestUtils.setField(other, "resultId", 51L);
        var otherRows = rows.stream().map(row -> new QuizJudgeStoredResultItem(51L, row.getQuestionNo(),
                row.getRubric(), row.getVerdict(), row.getReason())).toList();
        given(items.findByResultIdOrderByQuestionNoAscItemIdAsc(51L)).willReturn(otherRows);
        given(results.findById(51L)).willReturn(Optional.of(other));
        given(results.findById(999L)).willReturn(Optional.empty());
        assertThat(reader.read(other).target().revisionId()).isEqualTo(101L);
        for (Long id : List.of(51L, 999L)) {
            error(() -> policy.requireStillApproved(target, QuizRevisionDecision.approve(100L, 7L, id,
                    approvedSha, target.revisionSha256(), QuizFixtures.NOW)), QuizErrorCode.QUIZ_JUDGE_EVALUATION_INTEGRITY_FAILED);
        }
    }
    private void error(Runnable call, QuizErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    @Test void snapshot과_hash가_일치해도_201_300자_reason은_승인과_게시를_차단한다() {
        for (int length : new int[]{201, 300}) {
            storedReason(300, "가".repeat(length));
            assertThat(readerSettingsMatch()).isTrue();
            error(() -> policy.requireApprovable(target), QuizErrorCode.QUIZ_JUDGE_EVALUATION_INTEGRITY_FAILED);
            error(() -> policy.requireStillApproved(target, QuizRevisionDecision.approve(100L, 7L, 50L,
                    result.getResultSha256(), target.revisionSha256(), QuizFixtures.NOW)), QuizErrorCode.QUIZ_JUDGE_EVALUATION_INTEGRITY_FAILED);
        }
    }

    @Test void 저장된_200자_reason은_원문_그대로_승인할_수_있다() {
        storedReason(200, "가".repeat(200));
        assertThat(policy.requireApprovable(target).judgeEvaluationId()).isEqualTo(50L);
        assertThat(reader.read(result).evaluation().questions().get(0).item(JudgeRubric.TEXT_ANSWER_CORRECT)
                .reason()).isEqualTo("가".repeat(200));
    }

    private boolean readerSettingsMatch() {
        return series.getSettingsFingerprint().equals(QuizJudgeFingerprint.settingsFingerprint(series.settings()))
                && series.getSettingsFingerprint().equals(result.getSettingsFingerprint());
    }

    /** validator를 거치지 않고 저장 변조를 재현한다. 내용과 설정에 맞는 hash를 가진 artifact다. */
    private void storedReason(int limit, String reason) {
        ReflectionTestUtils.setField(series, "reasonMaxLength", limit);
        ReflectionTestUtils.setField(series, "settingsFingerprint", QuizJudgeFingerprint.settingsFingerprint(series.settings()));
        var evaluation = new QuizJudgeEvaluation(new QuizJudgeResponseParser(new ObjectMapper())
                .parse(QuizJudgeFixtures.output(3, (no, rubric) -> new String[]{"pass", reason})));
        String sha = QuizJudgeFingerprint.resultSha256(series.getInputSha256(), series.getSettingsFingerprint(),
                series.getPolicyVersion(), evaluation);
        result = new QuizJudgeStoredResult(job, new QuizJudgeResult(evaluation, series.settings(), series.getInputSha256(),
                series.getSettingsFingerprint(), sha, null), QuizFixtures.NOW);
        ReflectionTestUtils.setField(result, "resultId", 50L);
        rows = new ArrayList<>();
        for (var question : evaluation.questions()) for (var rubric : JudgeRubric.values()) {
            var item = question.item(rubric);
            rows.add(new QuizJudgeStoredResultItem(50L, question.questionNo(), rubric, item.verdict(), item.reason()));
        }
        given(results.findByRevisionId(100L)).willReturn(Optional.of(result));
        given(results.findById(50L)).willReturn(Optional.of(result));
        given(items.findByResultIdOrderByQuestionNoAscItemIdAsc(50L)).willReturn(rows);
    }
    @Test void 현재_prompt와_다른_저장_snapshot도_승인_근거를_유지한다() {
        ReflectionTestUtils.setField(series, "promptVersion", "historical-prompt-v0");
        ReflectionTestUtils.setField(series, "instructionsSha256", QuizJudgeFingerprint.sha256Hex("historical prompt"));
        ReflectionTestUtils.setField(series, "settingsFingerprint", QuizJudgeFingerprint.settingsFingerprint(series.settings()));
        setVerdict(JudgeVerdict.PASS);
        var basis = policy.requireApprovable(target);
        policy.requireStillApproved(target, QuizRevisionDecision.approve(100L, 7L, basis.judgeEvaluationId(),
                basis.judgeEvaluationSha256(), target.revisionSha256(), QuizFixtures.NOW));
    }
}
