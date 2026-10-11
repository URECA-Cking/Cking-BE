package kr.co.cking.quiz.domain;

import kr.co.cking.quiz.QuizFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuizTest {

    private static final Instant LATER = QuizFixtures.NOW.plusSeconds(60);

    @Test
    void 새_Quiz는_미게시이고_원본_텍스트_해시를_기록한다() {
        Quiz quiz = QuizFixtures.quiz(1L, 10L);

        assertThat(quiz.getPublicationStatus()).isEqualTo(QuizPublicationStatus.NOT_PUBLISHED);
        assertThat(quiz.getPublishedRevisionId()).isNull();
        assertThat(quiz.getContentTextSha256())
                .isEqualTo(QuizRevisionFingerprint.contentTextSha256(QuizFixtures.CONTENT_TEXT));
        assertThat(quiz.hasIntactContentText()).isTrue();
    }

    @Test
    void 원본_텍스트가_해시와_다르면_무결성이_깨진_것이다() {
        Quiz quiz = QuizFixtures.quiz(1L, 10L);
        ReflectionTestUtils.setField(quiz, "contentText", QuizFixtures.CONTENT_TEXT + " ");

        assertThat(quiz.hasIntactContentText()).isFalse();
    }

    @Test
    void 게시하면_게시_상태와_포인터가_함께_바뀌고_취소하면_함께_비워진다() {
        Quiz quiz = QuizFixtures.quiz(1L, 10L);

        quiz.publish(100L, LATER);
        assertThat(quiz.getPublicationStatus()).isEqualTo(QuizPublicationStatus.PUBLISHED);
        assertThat(quiz.getPublishedRevisionId()).isEqualTo(100L);
        assertThat(quiz.isPublishing(100L)).isTrue();
        assertThat(quiz.getPublishedAt()).isEqualTo(LATER);

        quiz.unpublish(LATER.plusSeconds(1));
        assertThat(quiz.getPublicationStatus()).isEqualTo(QuizPublicationStatus.UNPUBLISHED);
        assertThat(quiz.getPublishedRevisionId()).isNull();
        assertThat(quiz.isPublishing(100L)).isFalse();
        assertThat(quiz.getUnpublishedAt()).isEqualTo(LATER.plusSeconds(1));
    }

    @Test
    void 게시_중이_아니면_게시_취소할_수_없다() {
        Quiz quiz = QuizFixtures.quiz(1L, 10L);

        assertThatThrownBy(() -> quiz.unpublish(LATER)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 소유_Creator를_구분한다() {
        Quiz quiz = QuizFixtures.quiz(1L, 10L);

        assertThat(quiz.isOwnedBy(10L)).isTrue();
        assertThat(quiz.isOwnedBy(11L)).isFalse();
    }
}
