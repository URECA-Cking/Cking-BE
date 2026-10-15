package kr.co.cking.quiz.application;
import kr.co.cking.quiz.application.dto.QuizAttemptCommand;
import kr.co.cking.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
class QuizAttemptCommandTest {
    private final UUID id = UUID.randomUUID();
    private QuizAttemptCommand command(List<QuizAttemptCommand.Answer> answers) {
        return new QuizAttemptCommand(7L, 1L, 2L, id, 100L, answers);
    }
    @Test void 답안_순서가_달라도_동일한_fingerprint다() {
        var a = new QuizAttemptCommand.Answer(0, 1); var b = new QuizAttemptCommand.Answer(1, 0);
        assertThat(QuizAttemptHashes.request(command(List.of(a,b)))).isEqualTo(QuizAttemptHashes.request(command(List.of(b,a))));
    }
    @Test void 다른_답안_revision_대상은_다른_fingerprint다() {
        var a = List.of(new QuizAttemptCommand.Answer(0,0)); var c = command(a);
        assertThat(QuizAttemptHashes.request(command(List.of(new QuizAttemptCommand.Answer(0,1))))).isNotEqualTo(QuizAttemptHashes.request(c));
        assertThat(QuizAttemptHashes.request(new QuizAttemptCommand(7L,1L,2L,id,101L,a))).isNotEqualTo(QuizAttemptHashes.request(c));
        assertThat(QuizAttemptHashes.request(new QuizAttemptCommand(7L,1L,3L,id,100L,a))).isNotEqualTo(QuizAttemptHashes.request(c));
    }
    @Test void 빈_누락_중복_음수_답안을_거부한다() {
        for (var answers : List.of(List.<QuizAttemptCommand.Answer>of(), List.of(new QuizAttemptCommand.Answer(1,0)),
                List.of(new QuizAttemptCommand.Answer(0,0),new QuizAttemptCommand.Answer(0,1)), List.of(new QuizAttemptCommand.Answer(0,-1)))) {
            assertThatThrownBy(() -> command(answers)).isInstanceOf(BusinessException.class);
        }
    }
    @Test void 식별자와_UUID가_필수다() {
        assertThatThrownBy(() -> new QuizAttemptCommand(7L,1L,2L,null,100L,List.of(new QuizAttemptCommand.Answer(0,0)))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new QuizAttemptCommand(0L,1L,2L,id,100L,List.of(new QuizAttemptCommand.Answer(0,0)))).isInstanceOf(BusinessException.class);
    }
}
