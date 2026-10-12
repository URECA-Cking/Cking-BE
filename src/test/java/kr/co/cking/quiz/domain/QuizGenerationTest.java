package kr.co.cking.quiz.domain;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuizGenerationTest {

    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");

    @Test
    void 새_작업은_PENDING이고_보기_수는_4로_고정된다() {
        QuizGeneration generation = new QuizGeneration(10L, 7L, "req", "dQw4w9WgXcQ", "물의 순환", 5, NOW);

        assertThat(generation.getStatus()).isEqualTo(QuizGenerationStatus.PENDING);
        assertThat(generation.getQuestionCount()).isEqualTo(5);
        assertThat(generation.getOptionCount()).isEqualTo(4);
        assertThat(generation.getRequestFingerprint())
                .isEqualTo(QuizGeneration.fingerprint("dQw4w9WgXcQ", "물의 순환", 5, 4));
    }

    @Test
    void 요청_지문은_입력이_같으면_같고_하나라도_다르면_다르다() {
        String base = QuizGeneration.fingerprint("dQw4w9WgXcQ", "물의 순환", 3, 4);

        assertThat(QuizGeneration.fingerprint("dQw4w9WgXcQ", "물의 순환", 3, 4)).isEqualTo(base).hasSize(64);
        assertThat(QuizGeneration.fingerprint("aaaaaaaaaaa", "물의 순환", 3, 4)).isNotEqualTo(base);
        assertThat(QuizGeneration.fingerprint("dQw4w9WgXcQ", "물의 순환 ", 3, 4)).isNotEqualTo(base);
        assertThat(QuizGeneration.fingerprint("dQw4w9WgXcQ", "물의 순환", 5, 4)).isNotEqualTo(base);
        assertThat(QuizGeneration.fingerprint("ab", "c", 3, 4)).isNotEqualTo(QuizGeneration.fingerprint("a", "bc", 3, 4));
    }

    @Test
    void RUNNING_작업만_완료할_수_있다() {
        QuizGeneration generation = new QuizGeneration(10L, 7L, "req", "dQw4w9WgXcQ", "물의 순환", 3, NOW);
        assertThatThrownBy(() -> generation.succeed(1L, 100L, NOW)).isInstanceOf(IllegalStateException.class);

        ReflectionTestUtils.setField(generation, "status", QuizGenerationStatus.RUNNING);
        generation.succeed(1L, 100L, NOW.plusSeconds(5));

        assertThat(generation.getStatus()).isEqualTo(QuizGenerationStatus.SUCCEEDED);
        assertThat(generation.getQuizId()).isEqualTo(1L);
        assertThat(generation.getRevisionId()).isEqualTo(100L);
        assertThat(generation.getCompletedAt()).isEqualTo(NOW.plusSeconds(5));

        ReflectionTestUtils.setField(generation, "status", QuizGenerationStatus.UNKNOWN);
        assertThatThrownBy(() -> generation.succeed(1L, 100L, NOW)).isInstanceOf(IllegalStateException.class);
    }
}
