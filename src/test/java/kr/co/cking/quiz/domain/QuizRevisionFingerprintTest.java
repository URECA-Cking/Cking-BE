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

    // quiz-revision-v2 (Creator가 구성한 revision)

    private static final String PARENT_SHA = "2619d1bec536bc03c35b76e5096709ddb26a6aba192fd17c3bb6c28b67bdaf2b";

    /** 원본 그대로·수정(근거 비움)·추가 문항. 기대값은 문서의 v2 규칙을 Java와 별도로 구현해 계산했다. */
    private static List<QuizRevisionFingerprint.ComposedQuestion> composedQuestions() {
        List<QuizRevisionFingerprint.Question> ai = QuizFixtures.fingerprintQuestions();
        QuizRevisionFingerprint.Question second = ai.get(1);
        return List.of(
                new QuizRevisionFingerprint.ComposedQuestion(QuizQuestionChangeType.AI_UNCHANGED, 1000L, 1000L, ai.get(0)),
                new QuizRevisionFingerprint.ComposedQuestion(QuizQuestionChangeType.CREATOR_EDITED, 1001L, 1001L,
                        new QuizRevisionFingerprint.Question(second.question(), second.options(),
                                second.correctOptionIndex(), "구름이 비가 되어 내립니다.", "")),
                new QuizRevisionFingerprint.ComposedQuestion(QuizQuestionChangeType.CREATOR_ADDED, null, null,
                        new QuizRevisionFingerprint.Question("증발한 물은 무엇이 되나요?", List.of("구름", "돌"), 0,
                                "증발하면 구름이 됩니다.", "")));
    }

    private String v2Sha(Long parentId, String parentSha, List<QuizRevisionFingerprint.ComposedQuestion> questions) {
        return QuizRevisionFingerprint.revisionSha256V2(CONTENT_SHA, QuizFixtures.TITLE, QuizFixtures.PROMPT_VERSION,
                parentId, parentSha, questions);
    }

    @Test
    void v2는_문서화한_규칙의_고정_해시와_같고_같은_입력이면_같은_해시다() {
        assertThat(v2Sha(100L, PARENT_SHA, composedQuestions()))
                .isEqualTo("f3524d3ebefb82a8c06424b66ddb370cc8ae0f4ea65a2a2f8215692571b4a132")
                .isEqualTo(v2Sha(100L, PARENT_SHA, new ArrayList<>(composedQuestions())));
    }

    @Test
    void v2는_같은_문항_내용이어도_v1과_다른_해시다() {
        List<QuizRevisionFingerprint.ComposedQuestion> unchanged = QuizFixtures.fingerprintQuestions().stream()
                .map(question -> new QuizRevisionFingerprint.ComposedQuestion(QuizQuestionChangeType.AI_UNCHANGED,
                        1L, 1L, question))
                .toList();

        assertThat(v2Sha(100L, PARENT_SHA, unchanged)).isNotEqualTo(revisionSha(QuizFixtures.fingerprintQuestions()));
    }

    @Test
    void v2는_선택_순서_계보_부모_내용이_바뀌면_해시가_바뀐다() {
        String original = v2Sha(100L, PARENT_SHA, composedQuestions());
        List<QuizRevisionFingerprint.ComposedQuestion> base = composedQuestions();
        QuizRevisionFingerprint.ComposedQuestion first = base.get(0);
        List<UnaryOperator<QuizRevisionFingerprint.ComposedQuestion>> changes = List.of(
                q -> new QuizRevisionFingerprint.ComposedQuestion(QuizQuestionChangeType.CREATOR_EDITED,
                        q.originQuestionId(), q.previousQuestionId(), q.content()),
                q -> new QuizRevisionFingerprint.ComposedQuestion(q.changeType(), 999L, q.previousQuestionId(), q.content()),
                q -> new QuizRevisionFingerprint.ComposedQuestion(q.changeType(), q.originQuestionId(), 999L, q.content()),
                q -> new QuizRevisionFingerprint.ComposedQuestion(q.changeType(), q.originQuestionId(),
                        q.previousQuestionId(), new QuizRevisionFingerprint.Question(q.content().question(),
                        q.content().options(), 1, q.content().explanation(), q.content().sourceEvidence())),
                q -> new QuizRevisionFingerprint.ComposedQuestion(q.changeType(), q.originQuestionId(),
                        q.previousQuestionId(), new QuizRevisionFingerprint.Question(q.content().question(),
                        q.content().options(), q.content().correctOptionIndex(), q.content().explanation(), "")));
        for (UnaryOperator<QuizRevisionFingerprint.ComposedQuestion> change : changes) {
            List<QuizRevisionFingerprint.ComposedQuestion> questions = new ArrayList<>(base);
            questions.set(0, change.apply(first));
            assertThat(v2Sha(100L, PARENT_SHA, questions)).isNotEqualTo(original);
        }
        assertThat(v2Sha(100L, PARENT_SHA, List.of(base.get(1), base.get(0), base.get(2)))).isNotEqualTo(original);
        assertThat(v2Sha(100L, PARENT_SHA, base.subList(0, 2))).isNotEqualTo(original);
        assertThat(v2Sha(101L, PARENT_SHA, base)).isNotEqualTo(original);
        assertThat(v2Sha(100L, "f".repeat(64), base)).isNotEqualTo(original);
    }
}
