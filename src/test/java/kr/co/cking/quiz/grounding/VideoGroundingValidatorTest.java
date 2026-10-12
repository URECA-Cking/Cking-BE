package kr.co.cking.quiz.grounding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VideoGroundingValidatorTest {

    private final VideoGroundingValidator validator = new VideoGroundingValidator();

    @Test
    void 통과한_contentText는_원문_그대로_돌려준다() {
        VideoGrounding grounding = grounding("  물은 증발해 구름이 된다.\n", List.of(fact("01:41", "01:41.2")));

        assertThat(validator.validate(grounding)).isSameAs(grounding);
        assertThat(grounding.contentText()).isEqualTo("  물은 증발해 구름이 된다.\n");
    }

    @Test
    void contentText가_비었거나_공백이면_거부한다() {
        for (String contentText : new String[] {null, "", "   \n\t"}) {
            assertInvalid(grounding(contentText, List.of()));
        }
    }

    @Test
    void contentText는_50000자까지_허용하고_넘으면_거부한다() {
        assertThat(validator.validate(grounding("가".repeat(50_000), List.of())).contentText()).hasSize(50_000);
        assertInvalid(grounding("가".repeat(50_001), List.of()));
    }

    @Test
    void facts는_비어_있어도_된다() {
        assertThat(validator.validate(grounding("본문", List.of())).facts()).isEmpty();
    }

    @Test
    void 사실과_근거는_공백일_수_없다() {
        assertInvalid(grounding("본문", List.of(new GroundingFact(" ", EvidenceType.SPEECH, "근거", null, null))));
        assertInvalid(grounding("본문", List.of(new GroundingFact("사실", EvidenceType.SPEECH, "", null, null))));
        assertInvalid(grounding("본문", List.of(new GroundingFact("사실", null, "근거", null, null))));
    }

    @Test
    void 시각은_시작과_끝이_모두_없거나_모두_있어야_한다() {
        assertThat(validator.validate(grounding("본문", List.of(fact(null, null)))).facts()).hasSize(1);
        assertInvalid(grounding("본문", List.of(fact("00:10", null))));
        assertInvalid(grounding("본문", List.of(fact(null, "00:10"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"00:00", "01:41", "01:41.2", "59:59.9", "120:05"})
    void 분_2자리_이상과_초_00에서_59의_시각을_허용한다(String timestamp) {
        assertThat(validator.validate(grounding("본문", List.of(fact(timestamp, timestamp)))).facts()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1:41", "01:60", "01:4", "01:41.25", "01:41.", "01-41", "0141", " 01:41", "-01:41"})
    void 형식이_다른_시각은_거부한다(String timestamp) {
        assertInvalid(grounding("본문", List.of(fact(timestamp, "99:59"))));
    }

    @Test
    void 시작이_끝보다_늦으면_거부하고_같으면_허용한다() {
        assertInvalid(grounding("본문", List.of(fact("01:41.3", "01:41.2"))));
        assertInvalid(grounding("본문", List.of(fact("02:00", "01:59.9"))));
        assertThat(validator.validate(grounding("본문", List.of(fact("01:41.2", "01:41.2")))).facts()).hasSize(1);
        assertThat(validator.validate(grounding("본문", List.of(fact("09:59", "10:00")))).facts()).hasSize(1);
    }

    private VideoGrounding grounding(String contentText, List<GroundingFact> facts) {
        return new VideoGrounding(contentText, facts, GroundingPrompt.VERSION, "gemini-test");
    }

    private GroundingFact fact(String start, String end) {
        return new GroundingFact("물은 증발한다.", EvidenceType.SPEECH, "물이 증발합니다", start, end);
    }

    private void assertInvalid(VideoGrounding grounding) {
        assertThatThrownBy(() -> validator.validate(grounding))
                .isInstanceOfSatisfying(VideoGroundingException.class, exception ->
                        assertThat(exception.getFailure()).isEqualTo(VideoGroundingException.Failure.INVALID_RESPONSE));
    }
}
