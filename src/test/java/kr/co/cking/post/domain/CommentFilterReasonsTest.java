package kr.co.cking.post.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommentFilterReasonsTest {

    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");

    @ParameterizedTest
    @CsvSource({
            "spam:link, spam:link",
            "model, model",
            "classifier, classifier",
            "privacy:01012345678, privacy",
            "profanity:욕설 문장, profanity",
            "unknown:link, invalid",
            "댓글 문장, invalid"
    })
    void 허용한_유형과_이름만_남긴다(String reason, String expected) {
        assertThat(CommentFilterReasons.sanitize(reason)).isEqualTo(expected);
    }

    @Test
    void 차단된_댓글의_사유를_정리해_돌려주고_저장된_값은_바꾸지_않는다() {
        CreatorPostComment comment = comment();
        comment.markFiltered(CommentFilterAction.BLOCK, List.of("spam:link", "privacy:01012345678"), "rule", "model", NOW);

        assertThat(comment.blockReasons()).containsExactly("spam:link", "privacy");
        assertThat(comment.getFilterReasons()).isEqualTo("spam:link,privacy:01012345678");
    }

    @Test
    void 차단되지_않은_댓글은_사유가_있어도_돌려주지_않는다() {
        CreatorPostComment comment = comment();
        comment.markFiltered(CommentFilterAction.PASS, List.of("model"), "rule", "model", NOW);

        assertThat(comment.blockReasons()).isEmpty();
    }

    @Test
    void 판정_전인_댓글은_사유가_없다() {
        assertThat(comment().blockReasons()).isEmpty();
    }

    @Test
    void 본문을_고쳐_재판정_전이어도_이전_차단_사유를_돌려준다() {
        CreatorPostComment comment = comment();
        comment.markFiltered(CommentFilterAction.BLOCK, List.of("spam:link"), "rule", "model", NOW);

        comment.update("고친 본문", NOW.plusSeconds(10));

        assertThat(comment.getFilterStatus()).isEqualTo(CommentFilterStatus.PENDING);
        assertThat(comment.blockReasons()).containsExactly("spam:link");
    }

    private CreatorPostComment comment() {
        return new CreatorPostComment(100L, 20L, "댓글", NOW);
    }
}
