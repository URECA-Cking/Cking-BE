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

        quiz.publish(100L, QuizFixtures.revisionSha256(), LATER);
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
    void 재게시하면_게시_시각을_갱신하고_게시_취소_시각을_지운다() {
        Quiz quiz = QuizFixtures.quiz(1L, 10L);
        quiz.publish(100L, QuizFixtures.revisionSha256(), LATER);
        quiz.unpublish(LATER.plusSeconds(1));

        quiz.publish(100L, QuizFixtures.revisionSha256(), LATER.plusSeconds(2));

        assertThat(quiz.getPublicationStatus()).isEqualTo(QuizPublicationStatus.PUBLISHED);
        assertThat(quiz.getPublishedRevisionId()).isEqualTo(100L);
        assertThat(quiz.getPublishedAt()).isEqualTo(LATER.plusSeconds(2));
        assertThat(quiz.getUnpublishedAt()).isNull();
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

    @Test
    void 첫_게시가_내용을_잠그고_게시_revision과_해시를_고정하며_게시_취소해도_남는다() {
        Quiz quiz = QuizFixtures.quiz(1L, 10L);
        String sha = QuizFixtures.revisionSha256();
        assertThat(quiz.isContentLocked()).isFalse();

        quiz.publish(100L, sha, LATER);
        quiz.unpublish(LATER.plusSeconds(1));

        assertThat(quiz.isContentLocked()).isTrue();
        assertThat(quiz.getLockedRevisionId()).isEqualTo(100L);
        assertThat(quiz.getLockedRevisionSha256()).isEqualTo(sha);
        assertThat(quiz.getPublishedRevisionId()).isNull();
    }

    @Test
    void 잠긴_뒤에는_고정한_revision과_해시만_다시_게시할_수_있다() {
        Quiz quiz = QuizFixtures.quiz(1L, 10L);
        String sha = QuizFixtures.revisionSha256();
        quiz.publish(100L, sha, LATER);
        quiz.unpublish(LATER.plusSeconds(1));

        assertThat(quiz.canPublish(101L, sha)).isFalse();
        assertThat(quiz.canPublish(100L, "f".repeat(64))).isFalse();
        assertThatThrownBy(() -> quiz.publish(101L, sha, LATER)).isInstanceOf(IllegalStateException.class);
        assertThat(quiz.getPublicationStatus()).isEqualTo(QuizPublicationStatus.UNPUBLISHED);

        quiz.publish(100L, sha, LATER.plusSeconds(2));
        assertThat(quiz.isPublishing(100L)).isTrue();
        assertThat(quiz.getLockedRevisionId()).isEqualTo(100L);
    }

    @Test
    void 과거_게시_revision을_확인하지_못한_잠긴_Quiz는_어떤_revision도_게시할_수_없다() {
        Quiz quiz = QuizFixtures.quiz(1L, 10L);
        ReflectionTestUtils.setField(quiz, "publicationStatus", QuizPublicationStatus.UNPUBLISHED);
        ReflectionTestUtils.setField(quiz, "contentLocked", true);

        assertThat(quiz.canPublish(100L, QuizFixtures.revisionSha256())).isFalse();
        assertThatThrownBy(() -> quiz.publish(100L, QuizFixtures.revisionSha256(), LATER))
                .isInstanceOf(IllegalStateException.class);
    }
}
