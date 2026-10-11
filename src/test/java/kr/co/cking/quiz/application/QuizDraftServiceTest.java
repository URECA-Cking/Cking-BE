package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.quiz.QuizFixtures;
import kr.co.cking.quiz.application.dto.QuizDraft;
import kr.co.cking.quiz.application.dto.QuizDraftCommand;
import kr.co.cking.quiz.domain.Quiz;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizOption;
import kr.co.cking.quiz.domain.QuizPublicationStatus;
import kr.co.cking.quiz.domain.QuizQuestion;
import kr.co.cking.quiz.domain.QuizRevision;
import kr.co.cking.quiz.domain.QuizRevisionFingerprint;
import kr.co.cking.quiz.generation.GeneratedQuestion;
import kr.co.cking.quiz.generation.GeneratedQuiz;
import kr.co.cking.quiz.generation.QuizGenerationValidationException;
import kr.co.cking.quiz.generation.QuizGenerationValidator;
import kr.co.cking.quiz.generation.QuizInputException;
import kr.co.cking.quiz.generation.QuizGenerationInput;
import kr.co.cking.quiz.repository.QuizOptionRepository;
import kr.co.cking.quiz.repository.QuizQuestionRepository;
import kr.co.cking.quiz.repository.QuizRepository;
import kr.co.cking.quiz.repository.QuizRevisionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class QuizDraftServiceTest {

    private static final long CREATOR_ID = 10L;

    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final QuizRepository quizRepository = mock(QuizRepository.class);
    private final QuizRevisionRepository revisionRepository = mock(QuizRevisionRepository.class);
    private final QuizQuestionRepository questionRepository = mock(QuizQuestionRepository.class);
    private final QuizOptionRepository optionRepository = mock(QuizOptionRepository.class);
    private final AtomicLong ids = new AtomicLong(100);

    private QuizDraftService service;

    @BeforeEach
    void setUp() {
        service = new QuizDraftService(creatorRepository, quizRepository, revisionRepository, questionRepository,
                optionRepository, new QuizGenerationValidator(), Clock.fixed(QuizFixtures.NOW, ZoneOffset.UTC));
        given(creatorRepository.existsById(CREATOR_ID)).willReturn(true);
        given(quizRepository.save(any(Quiz.class))).willAnswer(withId("quizId"));
        given(revisionRepository.save(any(QuizRevision.class))).willAnswer(withId("revisionId"));
        given(questionRepository.save(any(QuizQuestion.class))).willAnswer(withId("questionId"));
        given(optionRepository.save(any(QuizOption.class))).willAnswer(withId("optionId"));
    }

    @Test
    void 검증된_생성_결과를_Quiz와_첫_revision으로_순서대로_저장한다() {
        QuizDraft draft = service.create(command(QuizFixtures.generated()));

        ArgumentCaptor<Quiz> quiz = ArgumentCaptor.forClass(Quiz.class);
        verify(quizRepository).save(quiz.capture());
        assertThat(quiz.getValue().getCreatorId()).isEqualTo(CREATOR_ID);
        assertThat(quiz.getValue().getSourceVideoId()).isEqualTo(QuizFixtures.VIDEO_ID);
        assertThat(quiz.getValue().getContentText()).isEqualTo(QuizFixtures.CONTENT_TEXT);
        assertThat(quiz.getValue().getPublicationStatus()).isEqualTo(QuizPublicationStatus.NOT_PUBLISHED);

        ArgumentCaptor<QuizRevision> revision = ArgumentCaptor.forClass(QuizRevision.class);
        verify(revisionRepository).save(revision.capture());
        assertThat(revision.getValue().getRevisionNo()).isEqualTo(1);
        assertThat(revision.getValue().getTitle()).isEqualTo(QuizFixtures.TITLE);
        assertThat(revision.getValue().getQuestionCount()).isEqualTo(2);
        assertThat(revision.getValue().getOptionCount()).isEqualTo(2);
        assertThat(revision.getValue().getRevisionSha256()).isEqualTo(QuizRevisionFingerprint.revisionSha256(
                QuizRevisionFingerprint.contentTextSha256(QuizFixtures.CONTENT_TEXT), QuizFixtures.TITLE,
                QuizFixtures.PROMPT_VERSION, QuizFixtures.fingerprintQuestions()));

        ArgumentCaptor<QuizQuestion> questions = ArgumentCaptor.forClass(QuizQuestion.class);
        verify(questionRepository, times(2)).save(questions.capture());
        assertThat(questions.getAllValues()).extracting(QuizQuestion::getQuestionNo).containsExactly(0, 1);
        assertThat(questions.getAllValues()).extracting(QuizQuestion::getCorrectOptionIndex).containsExactly(0, 1);
        assertThat(questions.getAllValues()).extracting(QuizQuestion::getQuestionText)
                .containsExactly("물이 증발하면 무엇이 되나요?", "구름은 무엇이 되어 내리나요?");

        ArgumentCaptor<QuizOption> options = ArgumentCaptor.forClass(QuizOption.class);
        verify(optionRepository, times(4)).save(options.capture());
        assertThat(options.getAllValues()).extracting(QuizOption::getOptionText).containsExactly("구름", "돌", "눈", "비");
        assertThat(options.getAllValues()).extracting(QuizOption::getOptionNo).containsExactly(0, 1, 0, 1);

        assertThat(draft.quizId()).isEqualTo(quiz.getValue().getQuizId());
        assertThat(draft.revisionId()).isEqualTo(revision.getValue().getRevisionId());
    }

    @Test
    void 기존_Validator가_거부하면_아무것도_저장하지_않는다() {
        List<GeneratedQuestion> questions = new ArrayList<>(QuizFixtures.questions());
        GeneratedQuestion first = questions.get(0);
        questions.set(0, new GeneratedQuestion(first.question(), first.options(), 2, first.explanation(),
                first.sourceEvidence()));

        assertThatThrownBy(() -> service.create(command(new GeneratedQuiz(questions, QuizFixtures.PROMPT_VERSION))))
                .isInstanceOf(QuizGenerationValidationException.class);
        assertThatThrownBy(() -> service.create(new QuizDraftCommand(CREATOR_ID, QuizFixtures.VIDEO_ID,
                QuizFixtures.TITLE, new QuizGenerationInput(" ", 2, 2), QuizFixtures.generated())))
                .isInstanceOf(QuizInputException.class);
        assertNothingSaved();
    }

    @Test
    void 저장_길이_상한을_넘으면_QUIZ_DRAFT_INVALID이고_아무것도_저장하지_않는다() {
        List<Function<GeneratedQuestion, GeneratedQuestion>> oversized = List.of(
                q -> new GeneratedQuestion("가".repeat(501), q.options(), q.correctOptionIndex(), q.explanation(),
                        q.sourceEvidence()),
                q -> new GeneratedQuestion(q.question(), List.of("가".repeat(201), q.options().get(1)),
                        q.correctOptionIndex(), q.explanation(), q.sourceEvidence()),
                q -> new GeneratedQuestion(q.question(), q.options(), q.correctOptionIndex(), "가".repeat(1001),
                        q.sourceEvidence()));
        for (Function<GeneratedQuestion, GeneratedQuestion> change : oversized) {
            List<GeneratedQuestion> questions = new ArrayList<>(QuizFixtures.questions());
            questions.set(0, change.apply(questions.get(0)));
            assertDraftInvalid(command(new GeneratedQuiz(questions, QuizFixtures.PROMPT_VERSION)));
        }
        String longEvidence = "가".repeat(1001);
        QuizGenerationInput longInput = new QuizGenerationInput(longEvidence, 1, 2);
        assertDraftInvalid(new QuizDraftCommand(CREATOR_ID, QuizFixtures.VIDEO_ID, QuizFixtures.TITLE, longInput,
                new GeneratedQuiz(List.of(new GeneratedQuestion("질문", List.of("가", "나"), 0, "해설", longEvidence)),
                        QuizFixtures.PROMPT_VERSION)));
        assertDraftInvalid(new QuizDraftCommand(CREATOR_ID, QuizFixtures.VIDEO_ID, "가".repeat(101),
                QuizFixtures.input(), QuizFixtures.generated()));
        assertDraftInvalid(new QuizDraftCommand(CREATOR_ID, QuizFixtures.VIDEO_ID, " ", QuizFixtures.input(),
                QuizFixtures.generated()));
        assertNothingSaved();
    }

    @Test
    void 상한과_같은_길이는_저장한다() {
        GeneratedQuestion question = new GeneratedQuestion("가".repeat(500), List.of("나".repeat(200), "다"), 0,
                "라".repeat(1000), "마".repeat(1000));
        QuizGenerationInput input = new QuizGenerationInput("마".repeat(1000), 1, 2);

        service.create(new QuizDraftCommand(CREATOR_ID, QuizFixtures.VIDEO_ID, "바".repeat(100), input,
                new GeneratedQuiz(List.of(question), QuizFixtures.PROMPT_VERSION)));

        verify(revisionRepository).save(any(QuizRevision.class));
    }

    @Test
    void videoId_형식이_아니면_저장하지_않는다() {
        for (String videoId : new String[] {null, "short", "dQw4w9WgXcQ1", "dQw4w9WgXc!"}) {
            assertDraftInvalid(new QuizDraftCommand(CREATOR_ID, videoId, QuizFixtures.TITLE, QuizFixtures.input(),
                    QuizFixtures.generated()));
        }
        assertNothingSaved();
    }

    @Test
    void 없는_Creator면_저장하지_않는다() {
        given(creatorRepository.existsById(CREATOR_ID)).willReturn(false);

        assertThatThrownBy(() -> service.create(command(QuizFixtures.generated())))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
        assertNothingSaved();
    }

    private QuizDraftCommand command(GeneratedQuiz generated) {
        return new QuizDraftCommand(CREATOR_ID, QuizFixtures.VIDEO_ID, QuizFixtures.TITLE, QuizFixtures.input(),
                generated);
    }

    private void assertDraftInvalid(QuizDraftCommand command) {
        assertThatThrownBy(() -> service.create(command))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(QuizErrorCode.QUIZ_DRAFT_INVALID));
    }

    private void assertNothingSaved() {
        verifyNoInteractions(quizRepository, revisionRepository, questionRepository, optionRepository);
    }

    private org.mockito.stubbing.Answer<Object> withId(String idField) {
        return invocation -> {
            Object entity = invocation.getArgument(0);
            ReflectionTestUtils.setField(entity, idField, ids.incrementAndGet());
            return entity;
        };
    }
}
