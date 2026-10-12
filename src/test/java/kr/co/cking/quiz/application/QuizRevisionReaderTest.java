package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.quiz.QuizFixtures;
import kr.co.cking.quiz.application.dto.QuizQuestionContent;
import kr.co.cking.quiz.domain.Quiz;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.domain.QuizOption;
import kr.co.cking.quiz.domain.QuizQuestion;
import kr.co.cking.quiz.domain.QuizRevision;
import kr.co.cking.quiz.repository.QuizOptionRepository;
import kr.co.cking.quiz.repository.QuizQuestionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 해시에 들어가지 않는 저장 번호·보기 수의 무결성 검증. 아래 손상 사례는 문항·보기 내용이 그대로라 revision 해시는
 * 일치하므로, 번호 검증이 없으면 통과한다.
 */
class QuizRevisionReaderTest {

    private static final long REVISION_ID = 100L;

    private final QuizQuestionRepository questionRepository = mock(QuizQuestionRepository.class);
    private final QuizOptionRepository optionRepository = mock(QuizOptionRepository.class);
    private final QuizRevisionReader reader = new QuizRevisionReader(questionRepository, optionRepository);
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
