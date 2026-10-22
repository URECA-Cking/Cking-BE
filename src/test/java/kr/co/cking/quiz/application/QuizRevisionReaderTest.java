package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.quiz.QuizFixtures;
import kr.co.cking.quiz.application.dto.QuizQuestionContent;
import kr.co.cking.quiz.domain.Quiz;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizOption;
import kr.co.cking.quiz.domain.QuizQuestion;
import kr.co.cking.quiz.domain.QuizQuestionChangeType;
import kr.co.cking.quiz.domain.QuizRevision;
import kr.co.cking.quiz.domain.QuizRevisionFingerprint;
import kr.co.cking.quiz.repository.QuizOptionRepository;
import kr.co.cking.quiz.repository.QuizQuestionRepository;
import kr.co.cking.quiz.repository.QuizRevisionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.mock;

/**
 * 해시에 들어가지 않는 저장 번호·보기 수의 무결성 검증. 아래 손상 사례는 문항·보기 내용이 그대로라 revision 해시는
 * 일치하므로, 번호 검증이 없으면 통과한다.
 */
class QuizRevisionReaderTest {

    private static final long REVISION_ID = 100L;

    private final QuizQuestionRepository questionRepository = mock(QuizQuestionRepository.class);
    private final QuizOptionRepository optionRepository = mock(QuizOptionRepository.class);
    private final QuizRevisionRepository revisionRepository = mock(QuizRevisionRepository.class);
    private final QuizRevisionReader reader = new QuizRevisionReader(questionRepository, optionRepository,
            revisionRepository);
    private final Quiz quiz = QuizFixtures.quiz(1L, 10L);
    private final QuizRevision revision = QuizFixtures.revision(REVISION_ID, 1L, 1);

    @Test
    void 정상_저장_데이터는_기존과_같이_순서대로_읽는다() {
        stub(QuizFixtures.questionRows(REVISION_ID), QuizFixtures.optionRows(REVISION_ID));

        List<QuizQuestionContent> verified = reader.readVerified(quiz, revision);

        assertThat(verified).isEqualTo(reader.read(REVISION_ID));
        assertThat(verified).extracting(QuizQuestionContent::questionNo).containsExactly(0, 1);
        assertThat(verified).extracting(QuizQuestionContent::options)
                .containsExactly(List.of("구름", "돌"), List.of("눈", "비"));
        assertThat(verified).extracting(QuizQuestionContent::correctOptionIndex).containsExactly(0, 1);
    }

    @Test
    void 문항_번호가_연속되지_않으면_무결성_오류다() {
        List<QuizQuestion> questions = new ArrayList<>(QuizFixtures.questionRows(REVISION_ID));
        QuizQuestion second = questions.get(1);
        QuizQuestion skipped = new QuizQuestion(REVISION_ID, 2, second.getQuestionText(),
                second.getCorrectOptionIndex(), second.getExplanation(), second.getSourceEvidence());
        ReflectionTestUtils.setField(skipped, "questionId", second.getQuestionId());
        questions.set(1, skipped);
        stub(questions, QuizFixtures.optionRows(REVISION_ID));

        assertIntegrityFailed(revision);
    }

    @Test
    void 보기_번호가_연속되지_않으면_무결성_오류다() {
        List<QuizOption> options = new ArrayList<>(QuizFixtures.optionRows(REVISION_ID));
        QuizOption second = options.get(1);
        options.set(1, new QuizOption(second.getQuestionId(), 2, second.getOptionText()));
        stub(QuizFixtures.questionRows(REVISION_ID), options);

        assertIntegrityFailed(revision);
    }

    @Test
    void 실제_보기_수가_revision의_보기_수와_다르면_무결성_오류다() {
        stub(QuizFixtures.questionRows(REVISION_ID), QuizFixtures.optionRows(REVISION_ID));
        QuizRevision threeOptions = new QuizRevision(1L, 1, QuizFixtures.TITLE, QuizFixtures.PROMPT_VERSION,
                2, 3, revision.getRevisionSha256(), QuizFixtures.NOW);
        ReflectionTestUtils.setField(threeOptions, "revisionId", REVISION_ID);

        assertIntegrityFailed(threeOptions);
    }

    // Creator가 구성한 revision(v2): AI revision 300(문항 3000·3001)을 부모로 한 revision 400

    private static final long AI_REVISION_ID = 300L;
    private static final long COMPOSED_REVISION_ID = 400L;
    private final QuizRevision aiRevision = QuizFixtures.revision(AI_REVISION_ID, 1L, 1);
    private final List<QuizQuestion> aiRows = List.of(
            row(3000L, AI_REVISION_ID, 0, "물이 증발하면 무엇이 되나요?", "증발한 물은 구름이 됩니다.", "물은 증발해 구름이 되고",
                    null, null, null),
            row(3001L, AI_REVISION_ID, 1, "구름은 무엇이 되어 내리나요?", "구름은 비가 되어 내립니다.", "구름은 비가 되어 내린다.",
                    null, null, null));
    private final List<QuizOption> optionPool = new ArrayList<>();

    @Test
    void 구성_revision은_v2_해시와_계보를_검증하고_빈_근거를_그대로_돌려준다() {
        List<QuizQuestion> rows = validComposedRows();
        QuizRevision composed = stubComposed(rows);

        List<QuizQuestionContent> verified = reader.readVerified(quiz, composed);

        assertThat(verified).extracting(QuizQuestionContent::sourceEvidence)
                .containsExactly("물은 증발해 구름이 되고", "", "");
        assertThat(verified).extracting(QuizQuestionContent::explanation)
                .containsExactly("증발한 물은 구름이 됩니다.", "편집한 해설", "추가 해설");
    }

    @Test
    void 해시를_기록한_뒤_내용이_바뀌면_v2_무결성_오류다() {
        List<QuizQuestion> rows = new ArrayList<>(validComposedRows());
        QuizRevision composed = stubComposed(rows);
        rows.set(0, row(4000L, COMPOSED_REVISION_ID, 0, "바뀐 질문", "증발한 물은 구름이 됩니다.", "물은 증발해 구름이 되고",
                QuizQuestionChangeType.AI_UNCHANGED, 3000L, 3000L));
        given(questionRepository.findByRevisionIdOrderByQuestionNoAsc(COMPOSED_REVISION_ID)).willReturn(rows);

        assertIntegrityFailed(composed);
    }

    @Test
    void 원본과_같은데_수정이라고_하거나_다른데_원본_그대로라고_하면_거부한다() {
        List<QuizQuestion> claimedEdit = new ArrayList<>(validComposedRows());
        claimedEdit.set(0, row(4000L, COMPOSED_REVISION_ID, 0, "물이 증발하면 무엇이 되나요?", "증발한 물은 구름이 됩니다.",
                "물은 증발해 구름이 되고", QuizQuestionChangeType.CREATOR_EDITED, 3000L, 3000L));
        assertIntegrityFailed(stubComposed(claimedEdit));

        List<QuizQuestion> claimedUnchanged = new ArrayList<>(validComposedRows());
        claimedUnchanged.set(1, row(4001L, COMPOSED_REVISION_ID, 1, "구름은 무엇이 되어 내리나요?", "편집한 해설", "",
                QuizQuestionChangeType.AI_UNCHANGED, 3001L, 3001L));
        assertIntegrityFailed(stubComposed(claimedUnchanged));
    }

    @Test
    void 직전_문항이_부모_revision이_아니거나_중복되면_거부한다() {
        QuizQuestion foreign = row(5000L, 999L, 0, "물이 증발하면 무엇이 되나요?", "증발한 물은 구름이 됩니다.",
                "물은 증발해 구름이 되고", null, null, null);
        options(5000L);
        List<QuizQuestion> otherParent = new ArrayList<>(validComposedRows());
        otherParent.set(0, row(4000L, COMPOSED_REVISION_ID, 0, "물이 증발하면 무엇이 되나요?", "증발한 물은 구름이 됩니다.",
                "물은 증발해 구름이 되고", QuizQuestionChangeType.AI_UNCHANGED, 5000L, 5000L));
        QuizRevision composed = stubComposed(otherParent, foreign);
        assertIntegrityFailed(composed);

        List<QuizQuestion> duplicated = new ArrayList<>(validComposedRows());
        duplicated.set(1, row(4001L, COMPOSED_REVISION_ID, 1, "물이 증발하면 무엇이 되나요?", "증발한 물은 구름이 됩니다.",
                "물은 증발해 구름이 되고", QuizQuestionChangeType.AI_UNCHANGED, 3000L, 3000L));
        assertIntegrityFailed(stubComposed(duplicated));
    }

    @Test
    void 원본_문항이_다른_Quiz의_revision이면_거부한다() {
        QuizRevision composed = stubComposed(validComposedRows());
        willReturn(List.of(QuizFixtures.revision(AI_REVISION_ID, 2L, 1))).given(revisionRepository).findAllById(any());

        assertIntegrityFailed(composed);
    }

    @Test
    void AI_생성_revision의_문항에_계보가_있으면_무결성_오류다() {
        List<QuizQuestion> questions = new ArrayList<>(QuizFixtures.questionRows(REVISION_ID));
        QuizQuestion first = questions.get(0);
        QuizQuestion withLineage = QuizQuestion.composed(REVISION_ID, 0, first.getQuestionText(),
                first.getCorrectOptionIndex(), first.getExplanation(), first.getSourceEvidence(),
                QuizQuestionChangeType.AI_UNCHANGED, 1L, 1L);
        ReflectionTestUtils.setField(withLineage, "questionId", first.getQuestionId());
        questions.set(0, withLineage);
        stub(questions, QuizFixtures.optionRows(REVISION_ID));

        assertIntegrityFailed(revision);
    }

    @Test
    void AI_생성_revision의_원본_문항에_원본_직전_참조만_붙어도_무결성_오류다() {
        for (String field : List.of("originQuestionId", "previousQuestionId")) {
            List<QuizQuestion> questions = QuizFixtures.questionRows(REVISION_ID);
            ReflectionTestUtils.setField(questions.get(0), field, 1L);
            stub(questions, QuizFixtures.optionRows(REVISION_ID));

            assertIntegrityFailed(revision);
        }
    }

    /** 원본 그대로(3000), 해설을 고치고 근거를 비운 수정(3001), 근거 없이 추가한 문항. */
    private List<QuizQuestion> validComposedRows() {
        return List.of(
                row(4000L, COMPOSED_REVISION_ID, 0, "물이 증발하면 무엇이 되나요?", "증발한 물은 구름이 됩니다.",
                        "물은 증발해 구름이 되고", QuizQuestionChangeType.AI_UNCHANGED, 3000L, 3000L),
                row(4001L, COMPOSED_REVISION_ID, 1, "구름은 무엇이 되어 내리나요?", "편집한 해설", null,
                        QuizQuestionChangeType.CREATOR_EDITED, 3001L, 3001L),
                row(4002L, COMPOSED_REVISION_ID, 2, "증발한 물은 무엇이 되나요?", "추가 해설", null,
                        QuizQuestionChangeType.CREATOR_ADDED, null, null));
    }

    /** 행 내용과 맞는 v2 해시로 revision 400을 만들고, 행·보기·참조 조회를 stub한다. */
    private QuizRevision stubComposed(List<QuizQuestion> rows, QuizQuestion... extraReferences) {
        optionPool.clear();
        aiRows.forEach(row -> options(row.getQuestionId()));
        rows.forEach(row -> options(row.getQuestionId()));
        for (QuizQuestion extra : extraReferences) {
            options(extra.getQuestionId());
        }
        List<QuizRevisionFingerprint.ComposedQuestion> composedQuestions = rows.stream()
                .map(row -> new QuizRevisionFingerprint.ComposedQuestion(row.getChangeType(), row.getOriginQuestionId(),
                        row.getPreviousQuestionId(), new QuizRevisionFingerprint.Question(row.getQuestionText(),
                        List.of("구름", "돌", "눈", "비"), row.getCorrectOptionIndex(), row.getExplanation(),
                        row.getSourceEvidence())))
                .toList();
        String sha = QuizRevisionFingerprint.revisionSha256V2(quiz.getContentTextSha256(), "편집한 제목",
                aiRevision.getPromptVersion(), AI_REVISION_ID, aiRevision.getRevisionSha256(), composedQuestions);
        QuizRevision composed = QuizRevision.composed(aiRevision, 2, "편집한 제목", rows.size(), 4, sha, 7L,
                QuizFixtures.NOW);
        ReflectionTestUtils.setField(composed, "revisionId", COMPOSED_REVISION_ID);

        List<QuizQuestion> references = new ArrayList<>(aiRows);
        references.addAll(List.of(extraReferences));
        given(questionRepository.findByRevisionIdOrderByQuestionNoAsc(COMPOSED_REVISION_ID)).willReturn(rows);
        // 같은 테스트에서 다시 stub할 때 이전 answer가 null 인자로 불리지 않도록 do-style로 stub한다.
        willAnswer(invocation -> {
            Iterable<Long> ids = invocation.getArgument(0);
            List<Long> requested = new ArrayList<>();
            ids.forEach(requested::add);
            return references.stream().filter(question -> requested.contains(question.getQuestionId())).toList();
        }).given(questionRepository).findAllById(any());
        willAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return optionPool.stream().filter(option -> ids.contains(option.getQuestionId())).toList();
        }).given(optionRepository).findByQuestionIdInOrderByQuestionIdAscOptionNoAsc(anyCollection());
        willReturn(List.of(aiRevision)).given(revisionRepository).findAllById(any());
        return composed;
    }

    private void options(long questionId) {
        List<String> texts = List.of("구름", "돌", "눈", "비");
        for (int optionNo = 0; optionNo < texts.size(); optionNo++) {
            optionPool.add(new QuizOption(questionId, optionNo, texts.get(optionNo)));
        }
    }

    private static QuizQuestion row(long questionId, long revisionId, int questionNo, String question,
                                    String explanation, String sourceEvidence, QuizQuestionChangeType changeType,
                                    Long originQuestionId, Long previousQuestionId) {
        QuizQuestion row = changeType == null
                ? new QuizQuestion(revisionId, questionNo, question, 0, explanation, sourceEvidence)
                : QuizQuestion.composed(revisionId, questionNo, question, 0, explanation, sourceEvidence, changeType,
                        originQuestionId, previousQuestionId);
        ReflectionTestUtils.setField(row, "questionId", questionId);
        return row;
    }

    private void stub(List<QuizQuestion> questions, List<QuizOption> options) {
        given(questionRepository.findByRevisionIdOrderByQuestionNoAsc(REVISION_ID)).willReturn(questions);
        given(optionRepository.findByQuestionIdInOrderByQuestionIdAscOptionNoAsc(anyCollection())).willReturn(options);
    }

    private void assertIntegrityFailed(QuizRevision target) {
        assertThatThrownBy(() -> reader.readVerified(quiz, target))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(QuizErrorCode.QUIZ_REVISION_INTEGRITY_FAILED));
    }
}
