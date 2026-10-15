package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.quiz.application.dto.QuizCompositionCommand;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class QuizCompositionCommandTest {
    private QuizCompositionCommand command(List<QuizCompositionCommand.Selection> selections) {
        return new QuizCompositionCommand(UUID.randomUUID(), 1L, "a".repeat(64), selections);
    }
    private QuizCompositionCommand.Content content(String evidence) {
        return new QuizCompositionCommand.Content(" 질문 ", List.of("A", "B", "C", "D"), 1, " 설명 ", evidence);
    }
    @Test void 정규화와_근거_미입력을_고정한다() {
        var a = command(List.of(new QuizCompositionCommand.Selection(null, content(null))));
        var b = command(List.of(new QuizCompositionCommand.Selection(null, content(" "))));
        assertThat(a.questions().getFirst().content().sourceEvidence()).isEmpty();
        assertThat(a.questions().getFirst().content().question()).isEqualTo("질문");
        assertThat(a.fingerprint()).isEqualTo(b.fingerprint());
    }
    @Test void 보기_순서와_선택_순서는_요청_내용이다() {
        var a = command(List.of(new QuizCompositionCommand.Selection(10L, null), new QuizCompositionCommand.Selection(11L, null)));
        var b = command(List.of(new QuizCompositionCommand.Selection(11L, null), new QuizCompositionCommand.Selection(10L, null)));
        assertThat(a.fingerprint()).isNotEqualTo(b.fingerprint());
    }
    @Test void 빈_문항과_6문항_중복선택을_거부한다() {
        assertThatThrownBy(() -> command(List.of())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> command(java.util.Collections.nCopies(6, new QuizCompositionCommand.Selection(null, content(null)))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> command(List.of(new QuizCompositionCommand.Selection(1L, null), new QuizCompositionCommand.Selection(1L, null))))
                .isInstanceOf(BusinessException.class);
    }
    @Test void 보기_수_중복_정답과_길이를_검증한다() {
        assertThatThrownBy(() -> new QuizCompositionCommand.Content("Q", List.of("A","B"), 0,"E",null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new QuizCompositionCommand.Content("Q", List.of("A"," A ","C","D"),0,"E",null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new QuizCompositionCommand.Content("Q", List.of("A","B","C","D"),4,"E",null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new QuizCompositionCommand.Content("Q".repeat(501), List.of("A","B","C","D"),0,"E",null)).isInstanceOf(BusinessException.class);
    }
}
