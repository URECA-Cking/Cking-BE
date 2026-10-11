package kr.co.cking.quiz.domain;

import kr.co.cking.quiz.QuizFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

class QuizRevisionFingerprintTest {

    private static final String CONTENT_SHA = QuizRevisionFingerprint.contentTextSha256(QuizFixtures.CONTENT_TEXT);

    /** 기대값은 문서의 규칙을 Java와 별도로 구현해 계산했다. 값이 바뀌면 규칙이 바뀐 것이다. */
    @Test
    void 문서화한_규칙의_고정_해시와_같다() {
        assertThat(CONTENT_SHA).isEqualTo("86664af63a4599fb331cc16cc4d6aba57729501937f534e5fd0064f28965394e");
        assertThat(revisionSha(QuizFixtures.fingerprintQuestions()))
                .isEqualTo("2619d1bec536bc03c35b76e5096709ddb26a6aba192fd17c3bb6c28b67bdaf2b");
    }

    @Test
    void 같은_내용이면_같은_해시다() {
        assertThat(revisionSha(QuizFixtures.fingerprintQuestions()))
                .isEqualTo(revisionSha(new ArrayList<>(QuizFixtures.fingerprintQuestions())));
    }

    @Test
    void 해시_대상_값이_하나라도_바뀌면_해시가_바뀐다() {
        String original = revisionSha(QuizFixtures.fingerprintQuestions());
        List<UnaryOperator<QuizRevisionFingerprint.Question>> changes = List.of(
                q -> new QuizRevisionFingerprint.Question(q.question() + "?", q.options(), q.correctOptionIndex(),
                        q.explanation(), q.sourceEvidence()),
                q -> new QuizRevisionFingerprint.Question(q.question(), List.of(q.options().get(0), "바위"),
                        q.correctOptionIndex(), q.explanation(), q.sourceEvidence()),
                q -> new QuizRevisionFingerprint.Question(q.question(), q.options(), 1 - q.correctOptionIndex(),
                        q.explanation(), q.sourceEvidence()),
                q -> new QuizRevisionFingerprint.Question(q.question(), q.options(), q.correctOptionIndex(),
                        q.explanation() + ".", q.sourceEvidence()),
                q -> new QuizRevisionFingerprint.Question(q.question(), q.options(), q.correctOptionIndex(),
                        q.explanation(), "물은"));
        for (UnaryOperator<QuizRevisionFingerprint.Question> change : changes) {
            List<QuizRevisionFingerprint.Question> questions = new ArrayList<>(QuizFixtures.fingerprintQuestions());
            questions.set(0, change.apply(questions.get(0)));
            assertThat(revisionSha(questions)).isNotEqualTo(original);
        }
        assertThat(QuizRevisionFingerprint.revisionSha256(
                QuizRevisionFingerprint.contentTextSha256("다른 원문"), QuizFixtures.TITLE, QuizFixtures.PROMPT_VERSION,
                QuizFixtures.fingerprintQuestions())).isNotEqualTo(original);
        assertThat(QuizRevisionFingerprint.revisionSha256(CONTENT_SHA, "다른 제목", QuizFixtures.PROMPT_VERSION,
                QuizFixtures.fingerprintQuestions())).isNotEqualTo(original);
        assertThat(QuizRevisionFingerprint.revisionSha256(CONTENT_SHA, QuizFixtures.TITLE, "quiz-mcq-v2",
                QuizFixtures.fingerprintQuestions())).isNotEqualTo(original);
    }

    @Test
    void 문항과_보기_순서가_바뀌면_해시가_바뀐다() {
        List<QuizRevisionFingerprint.Question> questions = QuizFixtures.fingerprintQuestions();
        String original = revisionSha(questions);

        assertThat(revisionSha(List.of(questions.get(1), questions.get(0)))).isNotEqualTo(original);
        QuizRevisionFingerprint.Question first = questions.get(0);
        QuizRevisionFingerprint.Question swapped = new QuizRevisionFingerprint.Question(first.question(),
                List.of(first.options().get(1), first.options().get(0)), first.correctOptionIndex(),
                first.explanation(), first.sourceEvidence());
        assertThat(revisionSha(List.of(swapped, questions.get(1)))).isNotEqualTo(original);
    }

    @Test
    void 값_경계를_옮긴_입력은_다른_해시다() {
        QuizRevisionFingerprint.Question base = QuizFixtures.fingerprintQuestions().get(0);
        QuizRevisionFingerprint.Question left = new QuizRevisionFingerprint.Question(base.question(),
                List.of("ab", "c"), 0, base.explanation(), base.sourceEvidence());
        QuizRevisionFingerprint.Question right = new QuizRevisionFingerprint.Question(base.question(),
                List.of("a", "bc"), 0, base.explanation(), base.sourceEvidence());

        assertThat(revisionSha(List.of(left))).isNotEqualTo(revisionSha(List.of(right)));
    }

    private String revisionSha(List<QuizRevisionFingerprint.Question> questions) {
        return QuizRevisionFingerprint.revisionSha256(CONTENT_SHA, QuizFixtures.TITLE, QuizFixtures.PROMPT_VERSION,
                questions);
    }
}
