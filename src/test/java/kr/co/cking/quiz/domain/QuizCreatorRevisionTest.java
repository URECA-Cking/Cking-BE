package kr.co.cking.quiz.domain;

import kr.co.cking.quiz.QuizFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Creator 구성 revision·문항 계보와 승인 정책의 도메인 규칙(이슈 #581). */
class QuizCreatorRevisionTest {

    private final QuizRevision parent = QuizFixtures.revision(100L, 1L, 1);

    @Test
    void AI_생성_revision은_v1_해시와_부모없는_원본으로_기록한다() {
        assertThat(parent.getOrigin()).isEqualTo(QuizRevisionOrigin.AI_GENERATED);
        assertThat(parent.getHashVersion()).isEqualTo(QuizRevisionFingerprint.VERSION);
        assertThat(parent.isCreatorComposed()).isFalse();
        assertThat(parent.getParentRevisionId()).isNull();
        assertThat(parent.getComposedByMemberId()).isNull();
    }

    @Test
    void Creator_구성_revision은_부모_ID와_해시_편집자와_v2를_기록한다() {
        QuizRevision composed = QuizRevision.composed(parent, 2, "편집한 제목", 3, 4, "c".repeat(64), 7L,
                QuizFixtures.NOW);

        assertThat(composed.getOrigin()).isEqualTo(QuizRevisionOrigin.CREATOR_COMPOSED);
        assertThat(composed.getHashVersion()).isEqualTo(QuizRevisionFingerprint.VERSION_V2);
        assertThat(composed.getQuizId()).isEqualTo(1L);
        assertThat(composed.getParentRevisionId()).isEqualTo(100L);
        assertThat(composed.getParentRevisionSha256()).isEqualTo(parent.getRevisionSha256());
        assertThat(composed.getPromptVersion()).isEqualTo(parent.getPromptVersion());
        assertThat(composed.getComposedByMemberId()).isEqualTo(7L);
        assertThat(composed.getComposedAt()).isEqualTo(QuizFixtures.NOW);
    }

    @Test
    void Creator_구성_revision은_1_5문항_4지선다만_허용한다() {
        for (int[] counts : new int[][]{{0, 4}, {6, 4}, {3, 2}, {3, 5}}) {
            assertThatThrownBy(() -> QuizRevision.composed(parent, 2, "제목", counts[0], counts[1], "c".repeat(64),
                    7L, QuizFixtures.NOW)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void 수정_추가_문항의_빈_근거는_빈_문자열로_저장하고_원본_그대로_문항은_근거를_보존한다() {
        assertThat(QuizQuestion.composed(200L, 0, "질문", 0, "해설", null,
                QuizQuestionChangeType.CREATOR_ADDED, null, null).getSourceEvidence()).isEmpty();
        assertThat(QuizQuestion.composed(200L, 1, "질문", 0, "해설", "   ",
                QuizQuestionChangeType.CREATOR_EDITED, 1000L, 1000L).getSourceEvidence()).isEmpty();
        QuizQuestion unchanged = QuizQuestion.composed(200L, 2, "질문", 0, "해설", "원본 근거",
                QuizQuestionChangeType.AI_UNCHANGED, 1001L, 1001L);

        assertThat(unchanged.getSourceEvidence()).isEqualTo("원본 근거");
        assertThat(unchanged.getChangeType()).isEqualTo(QuizQuestionChangeType.AI_UNCHANGED);
        assertThat(unchanged.getOriginQuestionId()).isEqualTo(1001L);
        assertThat(unchanged.getPreviousQuestionId()).isEqualTo(1001L);
    }

    @Test
    void 변경_유형과_원본_직전_참조가_맞지_않으면_만들_수_없다() {
        assertThatThrownBy(() -> QuizQuestion.composed(200L, 0, "질문", 0, "해설", "",
                QuizQuestionChangeType.CREATOR_ADDED, 1000L, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QuizQuestion.composed(200L, 0, "질문", 0, "해설", "",
                QuizQuestionChangeType.CREATOR_EDITED, 1000L, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QuizQuestion.composed(200L, 0, "질문", 0, "해설", "",
                QuizQuestionChangeType.AI_UNCHANGED, null, 1000L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void AI_문항의_변경_유형은_원본과의_내용_비교로_정한다() {
        QuizRevisionFingerprint.Question original = QuizFixtures.fingerprintQuestions().get(0);
        QuizRevisionFingerprint.Question same = new QuizRevisionFingerprint.Question(original.question(),
                List.copyOf(original.options()), original.correctOptionIndex(), original.explanation(),
                original.sourceEvidence());
        QuizRevisionFingerprint.Question emptiedEvidence = new QuizRevisionFingerprint.Question(original.question(),
                original.options(), original.correctOptionIndex(), original.explanation(), "");

        assertThat(QuizQuestionChangeType.ofAiQuestion(original, same)).isEqualTo(QuizQuestionChangeType.AI_UNCHANGED);
        assertThat(QuizQuestionChangeType.ofAiQuestion(original, emptiedEvidence))
                .isEqualTo(QuizQuestionChangeType.CREATOR_EDITED);
    }

    @Test
    void 기존_승인은_Judge_근거와_승인한_revision_해시를_고정한다() {
        QuizRevisionDecision approval = QuizRevisionDecision.approve(100L, 7L, 50L, "e".repeat(64),
                parent.getRevisionSha256(), QuizFixtures.NOW);

        assertThat(approval.getApprovalPolicy()).isEqualTo(QuizApprovalPolicyType.LEGACY_JUDGE_V1);
        assertThat(approval.getJudgeEvaluationId()).isEqualTo(50L);
        assertThat(approval.getApprovedRevisionSha256()).isEqualTo(parent.getRevisionSha256());
        assertThat(approval.getResponsibilityConfirmed()).isNull();
    }

    @Test
    void Creator_책임_승인은_Judge_근거없이_책임_확인과_승인한_revision_해시를_고정한다() {
        QuizRevisionDecision approval = QuizRevisionDecision.approveByCreatorReview(200L, 7L, "c".repeat(64),
                QuizFixtures.NOW);

        assertThat(approval.isApproved()).isTrue();
        assertThat(approval.getApprovalPolicy()).isEqualTo(QuizApprovalPolicyType.CREATOR_REVIEW_V1);
        assertThat(approval.getJudgeEvaluationId()).isNull();
        assertThat(approval.getJudgeEvaluationSha256()).isNull();
        assertThat(approval.getApprovedRevisionSha256()).isEqualTo("c".repeat(64));
        assertThat(approval.getResponsibilityConfirmed()).isTrue();
        assertThat(approval.getDecidedByMemberId()).isEqualTo(7L);
    }

    @Test
    void 거절은_승인_정책과_해시를_기록하지_않는다() {
        QuizRevisionDecision rejection = QuizFixtures.rejection(100L, 7L);

        assertThat(rejection.getApprovalPolicy()).isNull();
        assertThat(rejection.getApprovedRevisionSha256()).isNull();
        assertThat(rejection.getResponsibilityConfirmed()).isNull();
    }
}
