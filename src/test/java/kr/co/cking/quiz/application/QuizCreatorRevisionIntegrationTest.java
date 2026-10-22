package kr.co.cking.quiz.application;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.quiz.application.dto.QuizDraft;
import kr.co.cking.quiz.domain.QuizJudgeStoredResult;
import kr.co.cking.quiz.judge.JudgeVerdict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V73의 revision 계보·승인 정책·게시 고정 제약과 기존 승인·게시 흐름을 MySQL에서 확인한다(이슈 #581). */
@SpringBootTest
class QuizCreatorRevisionIntegrationTest {

    private static final String SHA = "c".repeat(64);

    @Autowired
    private ApplicationContext context;
    @Autowired
    private CreatorQuizCommandService commandService;
    @Autowired
    private QuizDraftService draftService;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private CreatorRepository creatorRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private QuizIntegrationData data;
    private Creator creator;
    private QuizDraft draft;
    private QuizJudgeStoredResult judgeResult;

    @BeforeEach
    void setUp() {
        data = new QuizIntegrationData(memberRepository, creatorRepository, draftService, jdbcTemplate);
        creator = data.creator();
        QuizJudgeIntegrationFixture fixture = new QuizJudgeIntegrationFixture(context);
        draft = fixture.draft(creator);
        judgeResult = fixture.saveResult(creator, draft, JudgeVerdict.PASS);
    }

    @AfterEach
    void cleanUp() {
        Long creatorId = creator.getCreatorId();
        jdbcTemplate.update("DELETE d FROM quiz_revision_decision d JOIN quiz_revision r ON d.revision_id = r.revision_id "
                + "JOIN quiz q ON r.quiz_id = q.quiz_id WHERE q.creator_id = ? AND r.origin = 'CREATOR_COMPOSED'", creatorId);
        jdbcTemplate.update("DELETE qq FROM quiz_question qq JOIN quiz_revision r ON qq.revision_id = r.revision_id "
                + "JOIN quiz q ON r.quiz_id = q.quiz_id WHERE q.creator_id = ? AND r.origin = 'CREATOR_COMPOSED'", creatorId);
        jdbcTemplate.update("DELETE r FROM quiz_revision r JOIN quiz q ON r.quiz_id = q.quiz_id "
                + "WHERE q.creator_id = ? AND r.origin = 'CREATOR_COMPOSED'", creatorId);
        data.cleanUp();
    }

    @Test
    void 기존_승인_게시는_LEGACY_JUDGE_V1과_게시_고정을_기록하고_게시_취소후에도_고정을_유지한다() {
        commandService.approve(creator.getMemberId(), draft.quizId(), draft.revisionId());
        commandService.publish(creator.getMemberId(), draft.quizId(), draft.revisionId());
        commandService.unpublish(creator.getMemberId(), draft.quizId());

        Map<String, Object> decision = jdbcTemplate.queryForMap("SELECT d.approval_policy, "
                + "d.approved_revision_sha256 = r.revision_sha256 AS hash_matches, d.responsibility_confirmed "
                + "FROM quiz_revision_decision d JOIN quiz_revision r ON d.revision_id = r.revision_id "
                + "WHERE d.revision_id = ?", draft.revisionId());
        Map<String, Object> quiz = jdbcTemplate.queryForMap("SELECT q.publication_status, q.content_locked, "
                + "q.locked_revision_id, q.locked_revision_sha256 = r.revision_sha256 AS hash_matches "
                + "FROM quiz q JOIN quiz_revision r ON r.revision_id = q.locked_revision_id WHERE q.quiz_id = ?",
                draft.quizId());
        assertThat(decision.get("approval_policy")).isEqualTo("LEGACY_JUDGE_V1");
        assertThat(((Number) decision.get("hash_matches")).intValue()).isEqualTo(1);
        assertThat(decision.get("responsibility_confirmed")).isNull();
        assertThat(quiz.get("publication_status")).isEqualTo("UNPUBLISHED");
        assertThat(quiz.get("content_locked")).isEqualTo(true);
        assertThat(((Number) quiz.get("locked_revision_id")).longValue()).isEqualTo(draft.revisionId());
        assertThat(((Number) quiz.get("hash_matches")).intValue()).isEqualTo(1);

        commandService.publish(creator.getMemberId(), draft.quizId(), draft.revisionId());
        assertThat(jdbcTemplate.queryForObject("SELECT publication_status FROM quiz WHERE quiz_id = ?", String.class,
                draft.quizId())).isEqualTo("PUBLISHED");
    }

    @Test
    void 기존_revision은_AI_GENERATED_v1이고_구성_revision은_같은_Quiz_부모와_실제_부모_해시를_요구한다() {
        Map<String, Object> ai = jdbcTemplate.queryForMap("SELECT origin, hash_version, parent_revision_id "
                + "FROM quiz_revision WHERE revision_id = ?", draft.revisionId());
        assertThat(ai.get("origin")).isEqualTo("AI_GENERATED");
        assertThat(ai.get("hash_version")).isEqualTo("quiz-revision-v1");
        assertThat(ai.get("parent_revision_id")).isNull();

        Long composed = insertComposed(draft.quizId(), draft.revisionId(), parentSha(draft.revisionId()), 4);
        assertThat(composed).isPositive();

        assertThatThrownBy(() -> insertComposed(draft.quizId(), draft.revisionId(), "f".repeat(64), 4))
                .isInstanceOf(DataIntegrityViolationException.class);
        QuizDraft other = data.draft(creator);
        assertThatThrownBy(() -> insertComposed(draft.quizId(), other.revisionId(), parentSha(other.revisionId()), 4))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertComposed(draft.quizId(), draft.revisionId(), parentSha(draft.revisionId()), 2))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_quiz_revision_origin");
    }

    @Test
    void 문항_계보는_변경_유형과_원본_직전_참조_조합을_DB가_강제한다() {
        Long composed = insertComposed(draft.quizId(), draft.revisionId(), parentSha(draft.revisionId()), 4);
        Long aiQuestion = jdbcTemplate.queryForObject(
                "SELECT question_id FROM quiz_question WHERE revision_id = ? AND question_no = 0", Long.class,
                draft.revisionId());

        insertQuestion(composed, 0, "AI_UNCHANGED", aiQuestion, aiQuestion);
        insertQuestion(composed, 1, "CREATOR_ADDED", null, null);
        assertThatThrownBy(() -> insertQuestion(composed, 2, "CREATOR_ADDED", aiQuestion, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_quiz_question_lineage");
        assertThatThrownBy(() -> insertQuestion(composed, 2, "CREATOR_EDITED", aiQuestion, null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_quiz_question_lineage");
        assertThatThrownBy(() -> insertQuestion(composed, 2, "CREATOR_EDITED", aiQuestion, Long.MAX_VALUE))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void Creator_책임_승인은_Judge_근거없이_책임_확인과_실제_revision_해시를_요구한다() {
        Long composed = insertComposed(draft.quizId(), draft.revisionId(), parentSha(draft.revisionId()), 4);

        assertThatThrownBy(() -> insertCreatorReview(composed, "f".repeat(64), true))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertCreatorReview(composed, SHA, false))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_quiz_revision_decision_judge");
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO quiz_revision_decision (revision_id, decision, "
                + "decided_by_member_id, judge_evaluation_id, judge_evaluation_sha256, approval_policy, "
                + "approved_revision_sha256, responsibility_confirmed, decided_at) VALUES (?, 'APPROVED', ?, 1, ?, "
                + "'CREATOR_REVIEW_V1', ?, TRUE, UTC_TIMESTAMP(6))", composed, creator.getMemberId(), SHA, SHA))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_quiz_revision_decision_judge");

        insertCreatorReview(composed, SHA, true);
        assertThat(jdbcTemplate.queryForObject("SELECT approval_policy FROM quiz_revision_decision WHERE revision_id = ?",
                String.class, composed)).isEqualTo("CREATOR_REVIEW_V1");
    }

    @Test
    void 게시_고정은_같은_Quiz의_실제_revision_해시만_가리키고_미게시는_잠글_수_없다() {
        QuizDraft other = data.draft(creator);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE quiz SET content_locked = TRUE WHERE quiz_id = ?", draft.quizId()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_quiz_lock_not_published");
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE quiz SET publication_status = 'UNPUBLISHED', "
                + "published_at = UTC_TIMESTAMP(6), content_locked = TRUE, locked_revision_id = ?, "
                + "locked_revision_sha256 = ? WHERE quiz_id = ?", draft.revisionId(), "f".repeat(64), draft.quizId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE quiz SET publication_status = 'UNPUBLISHED', "
                + "published_at = UTC_TIMESTAMP(6), content_locked = TRUE, locked_revision_id = ?, "
                + "locked_revision_sha256 = ? WHERE quiz_id = ?", other.revisionId(), parentSha(other.revisionId()),
                draft.quizId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE quiz SET publication_status = 'UNPUBLISHED', "
                + "published_at = UTC_TIMESTAMP(6) WHERE quiz_id = ?", draft.quizId()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_quiz_lock_unpublished");

        // V55가 허용한 미게시·published_at 있음 행은 잠금만 둘 수 있고 고정 revision은 가질 수 없다.
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE quiz SET published_at = UTC_TIMESTAMP(6), "
                + "content_locked = TRUE, locked_revision_id = ?, locked_revision_sha256 = ? WHERE quiz_id = ?",
                draft.revisionId(), parentSha(draft.revisionId()), draft.quizId()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_quiz_lock_not_published");
        jdbcTemplate.update("UPDATE quiz SET published_at = UTC_TIMESTAMP(6), content_locked = TRUE WHERE quiz_id = ?",
                draft.quizId());
    }

    @Test
    void NULL_비교로_정책별_CHECK를_우회할_수_없다() {
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO quiz_revision_decision (revision_id, decision, "
                        + "decided_by_member_id, judge_evaluation_id, judge_evaluation_sha256, approved_revision_sha256, "
                        + "decided_at) VALUES (?, 'APPROVED', ?, ?, ?, ?, UTC_TIMESTAMP(6))", draft.revisionId(),
                creator.getMemberId(), judgeResult.getResultId(), judgeResult.getResultSha256(), parentSha(draft.revisionId())))
                .as("승인 정책 NULL").isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_quiz_revision_decision_judge");

        Long composed = insertComposed(draft.quizId(), draft.revisionId(), parentSha(draft.revisionId()), 4);
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO quiz_revision_decision (revision_id, decision, "
                        + "decided_by_member_id, approval_policy, approved_revision_sha256, decided_at) "
                        + "VALUES (?, 'APPROVED', ?, 'CREATOR_REVIEW_V1', ?, UTC_TIMESTAMP(6))",
                composed, creator.getMemberId(), SHA))
                .as("책임 확인 NULL").isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_quiz_revision_decision_judge");

        Long aiQuestion = jdbcTemplate.queryForObject(
                "SELECT question_id FROM quiz_question WHERE revision_id = ? AND question_no = 0", Long.class,
                draft.revisionId());
        assertThatThrownBy(() -> insertQuestion(composed, 0, null, aiQuestion, aiQuestion))
                .as("변경 유형 NULL·원본과 직전 있음").isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_quiz_question_lineage");
        assertThatThrownBy(() -> insertQuestion(composed, 0, null, null, aiQuestion))
                .as("변경 유형 NULL·직전만 있음").isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_quiz_question_lineage");

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE quiz SET publication_status = 'PUBLISHED', "
                + "published_revision_id = ?, published_at = UTC_TIMESTAMP(6), content_locked = TRUE WHERE quiz_id = ?",
                draft.revisionId(), draft.quizId()))
                .as("게시 중 고정 revision NULL").isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_quiz_lock_published");
    }

    private String parentSha(Long revisionId) {
        return jdbcTemplate.queryForObject("SELECT revision_sha256 FROM quiz_revision WHERE revision_id = ?",
                String.class, revisionId);
    }

    private Long insertComposed(Long quizId, Long parentId, String parentSha, int optionCount) {
        Integer next = jdbcTemplate.queryForObject("SELECT MAX(revision_no) + 1 FROM quiz_revision WHERE quiz_id = ?",
                Integer.class, quizId);
        jdbcTemplate.update("INSERT INTO quiz_revision (quiz_id, revision_no, title, prompt_version, question_count, "
                        + "option_count, revision_sha256, origin, hash_version, parent_revision_id, parent_revision_sha256, "
                        + "composed_by_member_id, composed_at, created_at) VALUES (?, ?, '편집한 제목', 'quiz-mcq-v1', 2, ?, ?, "
                        + "'CREATOR_COMPOSED', 'quiz-revision-v2', ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                quizId, next, optionCount, SHA, parentId, parentSha, creator.getMemberId());
        return jdbcTemplate.queryForObject("SELECT revision_id FROM quiz_revision WHERE quiz_id = ? AND revision_no = ?",
                Long.class, quizId, next);
    }

    private void insertQuestion(Long revisionId, int questionNo, String changeType, Long originId, Long previousId) {
        jdbcTemplate.update("INSERT INTO quiz_question (revision_id, question_no, question_text, correct_option_index, "
                        + "explanation, source_evidence, change_type, origin_question_id, previous_question_id) "
                        + "VALUES (?, ?, '질문', 0, '해설', '', ?, ?, ?)",
                revisionId, questionNo, changeType, originId, previousId);
    }

    private void insertCreatorReview(Long revisionId, String approvedSha, boolean confirmed) {
        jdbcTemplate.update("INSERT INTO quiz_revision_decision (revision_id, decision, decided_by_member_id, "
                        + "approval_policy, approved_revision_sha256, responsibility_confirmed, decided_at) "
                        + "VALUES (?, 'APPROVED', ?, 'CREATOR_REVIEW_V1', ?, ?, UTC_TIMESTAMP(6))",
                revisionId, creator.getMemberId(), approvedSha, confirmed);
    }
}
