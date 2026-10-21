package kr.co.cking.quiz.domain;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class QuizCloseTest {
    private static final Instant NOW = Instant.parse("2026-10-19T00:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test void 게시_중_마감은_게시_포인터와_최초_고정을_보존하고_최종_상태가_된다() {
        Quiz quiz = published();
        quiz.close(NOW.plusSeconds(1));
        assertThat(quiz.getPublicationStatus().name()).isEqualTo("CLOSED");
        assertThat(quiz.getPublishedRevisionId()).isEqualTo(10L);
        assertThat(quiz.getLockedRevisionId()).isEqualTo(10L);
        assertThat(quiz.getLockedRevisionSha256()).isEqualTo(HASH);
        assertThat(quiz.isContentLocked()).isTrue();
        assertThat(quiz.isPublished()).isFalse();
        assertThat(quiz.canPublish(10L, HASH)).isFalse();
        assertThatThrownBy(() -> quiz.publish(10L, HASH, NOW)).hasMessageContaining("마감");
        assertThatThrownBy(() -> quiz.unpublish(NOW)).hasMessageContaining("마감");
        assertThat(quiz.getPublishedAt()).isEqualTo(NOW);
    }

    @Test void 게시_중지_마감은_NULL_포인터와_중지_이력을_보존한다() {
        Quiz quiz = published();
        quiz.unpublish(NOW.plusSeconds(1));
        quiz.close(NOW.plusSeconds(2));
        assertThat(quiz.getPublishedRevisionId()).isNull();
        assertThat(quiz.getUnpublishedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(quiz.getLockedRevisionId()).isEqualTo(10L);
    }

    @Test void 반복_마감은_최초_시각을_바꾸지_않는다() {
        Quiz quiz = published();
        quiz.close(NOW.plusSeconds(1));
        Instant updatedAt = quiz.getUpdatedAt();
        quiz.close(NOW.plusSeconds(2));
        assertThat(quiz.getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(quiz.getClosedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test void 미게시_마감은_거절한다() {
        Quiz quiz = new Quiz(1L, "dQw4w9WgXcQ", "원문", NOW);
        assertThatThrownBy(() -> quiz.close(NOW)).hasMessageContaining("게시");
        assertThat(quiz.getPublicationStatus()).isEqualTo(QuizPublicationStatus.NOT_PUBLISHED);
    }

    private static Quiz published() {
        Quiz quiz = new Quiz(1L, "dQw4w9WgXcQ", "원문", NOW);
        quiz.publish(10L, HASH, NOW);
        return quiz;
    }

}
