package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.quiz.QuizFixtures;
import kr.co.cking.quiz.application.dto.QuizDraft;
import kr.co.cking.quiz.application.dto.QuizDraftCommand;
import kr.co.cking.quiz.application.dto.QuizPublicationView;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizPublicationStatus;
import kr.co.cking.quiz.generation.GeneratedQuestion;
import kr.co.cking.quiz.generation.GeneratedQuiz;
import kr.co.cking.quiz.generation.QuizGenerationValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 운영 승인 Bean 구성에서 평가가 없거나 기준 미달일 때의 차단과 DB 제약을 실제 MySQL로 확인한다.
 */
@SpringBootTest
class QuizProductionPolicyIntegrationTest {

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

    @BeforeEach
    void setUp() {
        data = new QuizIntegrationData(memberRepository, creatorRepository, draftService, jdbcTemplate);
        creator = data.creator();
        draft = data.draft(creator);
    }

    @AfterEach
    void cleanUp() {
        data.cleanUp();
    }

    @Test
    void 운영_승인_정책은_저장된_평가를_검증하는_구현_하나뿐이다() {
        assertThat(context.getBeansOfType(QuizApprovalPolicy.class).values())
                .singleElement()
                .isInstanceOf(StoredJudgeQuizApprovalPolicy.class);
    }

    @Test
    void 운영_구성에서_승인은_차단되고_결정이_남지_않는다() {
        assertThatThrownBy(() -> commandService.approve(creator.getMemberId(), draft.quizId(), draft.revisionId()))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(QuizErrorCode.QUIZ_JUDGE_EVALUATION_REQUIRED));
        assertThat(data.decisionCount(draft.revisionId())).isZero();
    }

    @Test
    void 운영_구성에서_승인_기록이_있어도_게시는_차단된다() {
        draft = new QuizJudgeIntegrationFixture(context).draft(creator);
        insertApproval(draft.revisionId());

        assertThatThrownBy(() -> commandService.publish(creator.getMemberId(), draft.quizId(), draft.revisionId()))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(QuizErrorCode.QUIZ_JUDGE_EVALUATION_REQUIRED));
        assertThat(publicationStatus()).isEqualTo("NOT_PUBLISHED");
    }

    @Test
    void 운영_구성에서도_게시_취소와_거절은_허용된다() {
        draft = new QuizJudgeIntegrationFixture(context).draft(creator);
        insertApproval(draft.revisionId());
        jdbcTemplate.update("UPDATE quiz SET publication_status = 'PUBLISHED', published_revision_id = ?, "
                + "published_at = UTC_TIMESTAMP(6), content_locked = TRUE, locked_revision_id = ?, "
                + "locked_revision_sha256 = (SELECT revision_sha256 FROM quiz_revision WHERE revision_id = ?) "
                + "WHERE quiz_id = ?", draft.revisionId(), draft.revisionId(), draft.revisionId(), draft.quizId());

        QuizPublicationView view = commandService.unpublish(creator.getMemberId(), draft.quizId());

        assertThat(view.publicationStatus()).isEqualTo(QuizPublicationStatus.UNPUBLISHED);
        assertThat(view.publishedRevisionId()).isNull();
        assertThat(data.decisionCount(draft.revisionId())).isEqualTo(1);

        QuizDraft another = data.draft(creator);
        commandService.reject(creator.getMemberId(), another.quizId(), another.revisionId(), null);
        assertThat(data.decisionCount(another.revisionId())).isEqualTo(1);
    }

    @Test
    void Validator가_거부한_생성_결과는_어떤_행도_남기지_않는다() {
        int before = data.quizCount(creator);
        GeneratedQuestion first = QuizFixtures.questions().get(0);
        GeneratedQuiz invalid = new GeneratedQuiz(List.of(
                new GeneratedQuestion(first.question(), first.options(), 0, first.explanation(), "원문에 없는 근거"),
                QuizFixtures.questions().get(1)), QuizFixtures.PROMPT_VERSION);

        assertThatThrownBy(() -> draftService.create(new QuizDraftCommand(creator.getCreatorId(), QuizFixtures.VIDEO_ID,
                QuizFixtures.TITLE, QuizFixtures.input(), invalid)))
                .isInstanceOf(QuizGenerationValidationException.class);
        assertThat(data.quizCount(creator)).isEqualTo(before);
    }

    @Test
    void 문항과_보기를_순서대로_0_based_정답으로_저장한다() {
        List<String> options = jdbcTemplate.queryForList("""
                SELECT o.option_text FROM quiz_option o
                JOIN quiz_question q ON o.question_id = q.question_id
                WHERE q.revision_id = ? ORDER BY q.question_no, o.option_no
                """, String.class, draft.revisionId());
        List<Integer> answers = jdbcTemplate.queryForList(
                "SELECT correct_option_index FROM quiz_question WHERE revision_id = ? ORDER BY question_no",
                Integer.class, draft.revisionId());

        assertThat(options).containsExactly("구름", "돌", "눈", "비");
        assertThat(answers).containsExactly(0, 1);
    }

    @Test
    void DB는_Judge_근거_없는_승인과_포인터_없는_게시를_거부한다() {
        // 정책·승인 해시는 유효하게 두고 Judge 근거만 뺀다.
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO quiz_revision_decision "
                + "(revision_id, decision, decided_by_member_id, approval_policy, approved_revision_sha256, decided_at) "
                + "VALUES (?, 'APPROVED', ?, 'LEGACY_JUDGE_V1', "
                + "(SELECT revision_sha256 FROM quiz_revision WHERE revision_id = ?), UTC_TIMESTAMP(6))",
                draft.revisionId(), creator.getMemberId(), draft.revisionId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_quiz_revision_decision_judge");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE quiz SET publication_status = 'PUBLISHED' WHERE quiz_id = ?", draft.quizId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_quiz_publication");

        QuizDraft other = data.draft(creator);
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE quiz SET publication_status = 'PUBLISHED', "
                + "published_revision_id = ?, content_locked = TRUE, locked_revision_id = ?, locked_revision_sha256 = "
                + "(SELECT revision_sha256 FROM quiz_revision WHERE revision_id = ?) WHERE quiz_id = ?",
                other.revisionId(), other.revisionId(), other.revisionId(), draft.quizId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 같은_revision에_두_번째_결정은_DB가_거부한다() {
        draft = new QuizJudgeIntegrationFixture(context).draft(creator);
        commandService.reject(creator.getMemberId(), draft.quizId(), draft.revisionId(), null);

        assertThatThrownBy(() -> insertApproval(draft.revisionId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertApproval(Long revisionId) {
        var result = new QuizJudgeIntegrationFixture(context).saveResult(creator, draft,
                kr.co.cking.quiz.judge.JudgeVerdict.FAIL);
        jdbcTemplate.update("INSERT INTO quiz_revision_decision (revision_id, decision, decided_by_member_id, "
                + "judge_evaluation_id, judge_evaluation_sha256, approval_policy, approved_revision_sha256, decided_at) "
                + "VALUES (?, 'APPROVED', ?, ?, ?, 'LEGACY_JUDGE_V1', "
                + "(SELECT revision_sha256 FROM quiz_revision WHERE revision_id = ?), UTC_TIMESTAMP(6))",
                revisionId, creator.getMemberId(), result.getResultId(), result.getResultSha256(), revisionId);
    }

    private String publicationStatus() {
        return jdbcTemplate.queryForObject("SELECT publication_status FROM quiz WHERE quiz_id = ?", String.class,
                draft.quizId());
    }
}
