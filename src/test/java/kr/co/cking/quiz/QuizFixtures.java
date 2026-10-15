package kr.co.cking.quiz;

import kr.co.cking.quiz.domain.Quiz;
import kr.co.cking.quiz.domain.QuizOption;
import kr.co.cking.quiz.domain.QuizQuestion;
import kr.co.cking.quiz.domain.QuizRevision;
import kr.co.cking.quiz.domain.QuizRevisionDecision;
import kr.co.cking.quiz.domain.QuizRevisionFingerprint;
import kr.co.cking.quiz.generation.GeneratedQuestion;
import kr.co.cking.quiz.generation.GeneratedQuiz;
import kr.co.cking.quiz.generation.QuizGenerationInput;
import kr.co.cking.quiz.generation.QuizPromptVersion;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Quiz 테스트 공통 데이터. 문항 2개·보기 2개이며 두 번째 문항의 정답은 1번 보기(0-based)다. */
public final class QuizFixtures {

    public static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");
    public static final String VIDEO_ID = "dQw4w9WgXcQ";
    public static final String TITLE = "물의 순환";
    public static final String CONTENT_TEXT = "물은 증발해 구름이 되고, 구름은 비가 되어 내린다.";
    public static final String PROMPT_VERSION = QuizPromptVersion.V1.value();

    private QuizFixtures() {
    }

    public static QuizGenerationInput input() {
        return new QuizGenerationInput(CONTENT_TEXT, 2, 2);
    }

    public static GeneratedQuiz generated() {
        return new GeneratedQuiz(questions(), PROMPT_VERSION);
    }

    public static List<GeneratedQuestion> questions() {
        return List.of(
                new GeneratedQuestion("물이 증발하면 무엇이 되나요?", List.of("구름", "돌"), 0,
                        "증발한 물은 구름이 됩니다.", "물은 증발해 구름이 되고"),
                new GeneratedQuestion("구름은 무엇이 되어 내리나요?", List.of("눈", "비"), 1,
                        "구름은 비가 되어 내립니다.", "구름은 비가 되어 내린다."));
    }

    public static List<QuizRevisionFingerprint.Question> fingerprintQuestions() {
        return questions().stream()
                .map(question -> new QuizRevisionFingerprint.Question(question.question(), question.options(),
                        question.correctOptionIndex(), question.explanation(), question.sourceEvidence()))
                .toList();
    }

    public static Quiz quiz(Long quizId, Long creatorId) {
        Quiz quiz = new Quiz(creatorId, VIDEO_ID, CONTENT_TEXT, NOW);
        ReflectionTestUtils.setField(quiz, "quizId", quizId);
        return quiz;
    }

    /** {@link #questions()}로 만든 revision의 v1 해시. */
    public static String revisionSha256() {
        return QuizRevisionFingerprint.revisionSha256(
                QuizRevisionFingerprint.contentTextSha256(CONTENT_TEXT), TITLE, PROMPT_VERSION, fingerprintQuestions());
    }

    /** 저장 내용과 맞는 해시를 가진 revision. */
    public static QuizRevision revision(Long revisionId, Long quizId, int revisionNo) {
        return revision(revisionId, quizId, revisionNo, revisionSha256());
    }

    public static QuizRevision revision(Long revisionId, Long quizId, int revisionNo, String revisionSha256) {
        QuizRevision revision = new QuizRevision(quizId, revisionNo, TITLE, PROMPT_VERSION, 2, 2, revisionSha256, NOW);
        ReflectionTestUtils.setField(revision, "revisionId", revisionId);
        return revision;
    }

    /** {@link #questions()}를 저장한 문항 행. questionId는 {@code revisionId * 10 + 문항 순서}다. */
    public static List<QuizQuestion> questionRows(Long revisionId) {
        List<QuizQuestion> rows = new ArrayList<>();
        List<GeneratedQuestion> questions = questions();
        for (int questionNo = 0; questionNo < questions.size(); questionNo++) {
            GeneratedQuestion question = questions.get(questionNo);
            QuizQuestion row = new QuizQuestion(revisionId, questionNo, question.question(),
                    question.correctOptionIndex(), question.explanation(), question.sourceEvidence());
            ReflectionTestUtils.setField(row, "questionId", revisionId * 10 + questionNo);
            rows.add(row);
        }
        return rows;
    }

    public static List<QuizOption> optionRows(Long revisionId) {
        List<QuizOption> rows = new ArrayList<>();
        List<GeneratedQuestion> questions = questions();
        for (int questionNo = 0; questionNo < questions.size(); questionNo++) {
            List<String> options = questions.get(questionNo).options();
            for (int optionNo = 0; optionNo < options.size(); optionNo++) {
                rows.add(new QuizOption(revisionId * 10 + questionNo, optionNo, options.get(optionNo)));
            }
        }
        return rows;
    }

    public static QuizRevisionDecision approval(Long revisionId, Long memberId) {
        return QuizRevisionDecision.approve(revisionId, memberId, 900L, "e".repeat(64), revisionSha256(), NOW);
    }

    public static QuizRevisionDecision rejection(Long revisionId, Long memberId) {
        return QuizRevisionDecision.reject(revisionId, memberId, "오답이 있습니다.", NOW);
    }
}
