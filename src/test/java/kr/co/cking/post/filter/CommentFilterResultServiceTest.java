package kr.co.cking.post.filter;

import kr.co.cking.post.domain.CommentFilterAction;
import kr.co.cking.post.domain.CommentFilterStatus;
import kr.co.cking.post.domain.CreatorPostComment;
import kr.co.cking.post.repository.CreatorPostCommentRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class CommentFilterResultServiceTest {

    private static final Long COMMENT_ID = 1L;
    private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");
    private static final CommentFilterResult BLOCK = new CommentFilterResult(
            CommentFilterAction.BLOCK, List.of("spam:link"), "rule-1", "model-1");

    private final CreatorPostCommentRepository repository = mock(CreatorPostCommentRepository.class);
    private final CommentFilterResultService service =
            new CommentFilterResultService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void 판정을_요청한_본문과_같으면_결과를_저장한다() {
        CreatorPostComment comment = stubComment("본문");

        boolean saved = service.saveResult(COMMENT_ID, "본문", BLOCK);

        assertThat(saved).isTrue();
        assertThat(comment.getFilterStatus()).isEqualTo(CommentFilterStatus.DONE);
        assertThat(comment.isBlocked()).isTrue();
    }

    @Test
    void 판정_중_본문이_수정됐으면_이전_결과를_버린다() {
        CreatorPostComment comment = stubComment("본문");
        comment.update("수정한 본문", NOW);

        boolean saved = service.saveResult(COMMENT_ID, "본문", BLOCK);

        assertThat(saved).isFalse();
        assertThat(comment.getFilterStatus()).isEqualTo(CommentFilterStatus.PENDING);
        assertThat(comment.isBlocked()).isFalse();
    }

    @Test
    void 수정했다가_원래_본문으로_돌아왔으면_이전_결과가_지금_본문에도_유효해_저장한다() {
        CreatorPostComment comment = stubComment("본문");
        comment.update("수정한 본문", NOW);
        comment.update("본문", NOW.plusSeconds(1));

        assertThat(service.saveResult(COMMENT_ID, "본문", BLOCK)).isTrue();
        assertThat(comment.isBlocked()).isTrue();
    }

    @Test
    void 수정_시각이_같아도_본문이_다르면_버린다() {
        CreatorPostComment comment = stubComment("본문");
        comment.update("수정한 본문", NOW);

        assertThat(service.saveResult(COMMENT_ID, "본문", BLOCK)).isFalse();
    }

    @Test
    void 이미_판정을_마친_댓글이나_삭제된_댓글의_결과는_버린다() {
        CreatorPostComment done = stubComment("본문");
        done.markFiltered(CommentFilterAction.PASS, List.of(), "rule-0", "model-0", NOW);

        assertThat(service.saveResult(COMMENT_ID, "본문", BLOCK)).isFalse();
        assertThat(done.isBlocked()).isFalse();

        given(repository.findByIdForUpdate(2L)).willReturn(Optional.empty());
        assertThat(service.saveResult(2L, "본문", BLOCK)).isFalse();
    }

    @Test
    void 판정_중_수정된_댓글에는_이전_본문의_실패를_기록하지_않는다() {
        CreatorPostComment comment = stubComment("본문");
        comment.update("수정한 본문", NOW);

        assertThat(service.saveFailure(COMMENT_ID, "본문")).isFalse();
        assertThat(comment.getFilterStatus()).isEqualTo(CommentFilterStatus.PENDING);
    }

    @Test
    void 본문이_같으면_실패를_기록하고_이전_BLOCK은_유지한다() {
        CreatorPostComment comment = stubComment("본문");
        comment.markFiltered(CommentFilterAction.BLOCK, List.of("spam:link"), "rule-0", "model-0", NOW);
        comment.update("본문", NOW.plusSeconds(1));

        assertThat(service.saveFailure(COMMENT_ID, "본문")).isTrue();
        assertThat(comment.getFilterStatus()).isEqualTo(CommentFilterStatus.FAILED);
        assertThat(comment.isBlocked()).isTrue();
    }

    @Test
    void 개인정보로_막힌_댓글은_수정_후_재판정이_실패해도_원문_보기가_열리지_않는다() {
        CreatorPostComment comment = stubComment("본문");
        comment.markFiltered(CommentFilterAction.BLOCK, List.of("privacy:phone"), "rule-0", "model-0", NOW);
        comment.update("010-0000-0000", NOW.plusSeconds(1));

        assertThat(service.saveFailure(COMMENT_ID, "010-0000-0000")).isTrue();

        assertThat(comment.isBlocked()).isTrue();
        assertThat(comment.isBlockedForPrivacy()).isTrue();
    }

    @Test
    void 판정을_마친_댓글은_판정_대상이_아니다() {
        CreatorPostComment comment = new CreatorPostComment(10L, 20L, "본문", NOW);
        given(repository.findById(COMMENT_ID)).willReturn(Optional.of(comment));
        assertThat(service.findTarget(COMMENT_ID)).contains(new CommentFilterResultService.Target("본문"));

        comment.markFiltered(CommentFilterAction.PASS, List.of(), "rule-1", "model-1", NOW);
        assertThat(service.findTarget(COMMENT_ID)).isEmpty();
    }

    private CreatorPostComment stubComment(String content) {
        CreatorPostComment comment = new CreatorPostComment(10L, 20L, content, NOW);
        given(repository.findByIdForUpdate(COMMENT_ID)).willReturn(Optional.of(comment));
        return comment;
    }
}
