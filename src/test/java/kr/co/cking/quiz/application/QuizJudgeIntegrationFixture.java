package kr.co.cking.quiz.application;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.quiz.QuizGenerationFixtures;
import kr.co.cking.quiz.application.dto.QuizDraft;
import kr.co.cking.quiz.application.dto.QuizDraftCommand;
import kr.co.cking.quiz.domain.*;
import kr.co.cking.quiz.generation.QuizGenerationInput;
import kr.co.cking.quiz.judge.*;
import kr.co.cking.quiz.repository.*;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Provider와 Dispatcher 없이 실제 revision에 연결된 평가 행을 저장하는 CI 전용 fixture. */
class QuizJudgeIntegrationFixture {
    private final ApplicationContext context;
    QuizJudgeIntegrationFixture(ApplicationContext context) { this.context = context; }

    QuizDraft draft(Creator creator) {
        return context.getBean(QuizDraftService.class).create(new QuizDraftCommand(creator.getCreatorId(),
                QuizGenerationFixtures.VIDEO_ID, "물의 순환", new QuizGenerationInput(QuizGenerationFixtures.CONTENT_TEXT,
                3, 4), QuizGenerationFixtures.generated(3)));
    }

    QuizJudgeStoredResult saveResult(Creator creator, QuizDraft draft, JudgeVerdict verdict) {
        var quiz = context.getBean(QuizRepository.class).findById(draft.quizId()).orElseThrow();
        var revision = context.getBean(QuizRevisionRepository.class).findById(draft.revisionId()).orElseThrow();
        var questions = context.getBean(QuizRevisionReader.class).readVerified(quiz, revision).stream()
                .map(q -> new QuizJudgeInput.Question(q.questionNo(), q.question(), q.options(), q.correctOptionIndex(),
                        q.explanation(), q.sourceEvidence())).toList();
        var input = new QuizJudgeInput(quiz.getContentText(), questions);
        var mapper = new ObjectMapper();
        var settings = QuizJudgeSettings.current("fake-model", "medium", 8192, 200, mapper);
        String inputSha = QuizJudgeFingerprint.inputSha256(input);
        var pendingSeries = new QuizJudgeSeries(draft.quizId(), draft.revisionId(), quiz.getContentTextSha256(),
                revision.getRevisionSha256(), inputSha, settings, Instant.now());
        pendingSeries.allocateAttempt(Instant.now());
        var series = context.getBean(QuizJudgeSeriesRepository.class).saveAndFlush(pendingSeries);
        var job = context.getBean(QuizJudgeJobRepository.class).saveAndFlush(new QuizJudgeJob(series,
                creator.getCreatorId(), creator.getMemberId(), UUID.randomUUID().toString(), 1, Instant.now()));
        var evaluation = new QuizJudgeEvaluationValidator().validate(new QuizJudgeResponseParser(mapper).parse(
                QuizJudgeFixtures.output(questions.size(), (no, rubric) -> new String[]{verdict.value(), "근거"})),
                questions.size(), 200);
        var value = new QuizJudgeResult(evaluation, settings, inputSha, series.getSettingsFingerprint(),
                QuizJudgeFingerprint.resultSha256(inputSha, series.getSettingsFingerprint(), settings.policyVersion(), evaluation), null);
        var store = context.getBean(QuizJudgeEvaluationStore.class);
        assertThat(store.claim(job.getJobId(), Instant.now())).isTrue();
        assertThat(store.completeRunning(job.getJobId(), value,
                new QuizJudgeEvaluationStore.ExecutionDeadline(Long.MAX_VALUE, () -> 0L)))
                .isEqualTo(QuizJudgeEvaluationStore.Completion.SAVED);
        return context.getBean(QuizJudgeStoredResultRepository.class).findByJobId(job.getJobId()).orElseThrow();
    }
}
