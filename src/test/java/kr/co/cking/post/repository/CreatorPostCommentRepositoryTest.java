package kr.co.cking.post.repository;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.post.domain.CommentFilterAction;
import kr.co.cking.post.domain.CreatorPost;
import kr.co.cking.post.domain.CreatorPostComment;
import kr.co.cking.post.domain.PostVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * 실제 MySQL에서 가려진 댓글(필터 차단, 관리자 숨김)을 조회자별로 거르는 쿼리를 확인한다(이슈 #721).
 *
 * <p>스페이스 주인이 아닌 조회자는 가려진 댓글 중 본인이 쓴 것만 보고, 비로그인(조회자 null)은 가려진 댓글을 보지 못한다.
 * 목록 페이지 크기와 전체 개수, 게시글의 댓글 수가 같은 기준이어야 한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class CreatorPostCommentRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");

    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private CreatorRepository creatorRepository;
    @Autowired
    private CreatorPostRepository postRepository;
    @Autowired
    private CreatorPostCommentRepository commentRepository;

    private Long authorId;
    private Long otherId;
    private Long adminId;
    private Long postId;
    private Long passedId;
    private Long pendingId;
    private Long blockedId;
    private Long passAfterBlockOtherId;
    private Long hiddenId;

    @BeforeEach
    void setUp() {
        Member owner = memberRepository.saveAndFlush(new Member("댓글 가림 테스트 Creator", null, null, MemberRole.USER));
        Member author = memberRepository.saveAndFlush(new Member("댓글 가림 테스트 작성자", null, null, MemberRole.USER));
        Member other = memberRepository.saveAndFlush(new Member("댓글 가림 테스트 다른 회원", null, null, MemberRole.USER));
        Member admin = memberRepository.saveAndFlush(new Member("댓글 가림 테스트 관리자", null, null, MemberRole.ADMIN));
        Creator creator = creatorRepository.saveAndFlush(new Creator(owner.getMemberId(), "댓글 가림 테스트 Creator"));
        authorId = author.getMemberId();
        otherId = other.getMemberId();
        adminId = admin.getMemberId();
        postId = postRepository.saveAndFlush(
                new CreatorPost(creator.getCreatorId(), "본문", PostVisibility.PUBLIC, NOW)).getPostId();

        // 작성 순서: 통과(작성자) → 미판정(다른 회원) → 차단(작성자) → 통과(다른 회원) → 관리자 숨김(작성자)
        CreatorPostComment passed = comment(authorId, NOW);
        passed.markFiltered(CommentFilterAction.PASS, List.of(), "rule-1", "model-1", NOW);
        passedId = commentRepository.saveAndFlush(passed).getCommentId();
        pendingId = commentRepository.saveAndFlush(comment(otherId, NOW.plusSeconds(1))).getCommentId();
        CreatorPostComment blocked = comment(authorId, NOW.plusSeconds(2));
        blocked.markFiltered(CommentFilterAction.BLOCK, List.of("profanity:test"), "rule-1", "model-1", NOW);
        blockedId = commentRepository.saveAndFlush(blocked).getCommentId();
        CreatorPostComment passAfterBlock = comment(otherId, NOW.plusSeconds(3));
        passAfterBlock.markFiltered(CommentFilterAction.PASS, List.of(), "rule-1", "model-1", NOW);
        passAfterBlockOtherId = commentRepository.saveAndFlush(passAfterBlock).getCommentId();
        CreatorPostComment hidden = comment(authorId, NOW.plusSeconds(4));
        hidden.hideByAdmin(adminId, NOW);
        hiddenId = commentRepository.saveAndFlush(hidden).getCommentId();
    }

    @Test
    void 스페이스_주인용_조회는_가려진_댓글까지_작성_순으로_모두_준다() {
        List<Long> ids = commentRepository.findByPostIdOldestFirst(postId, PageRequest.of(0, 20)).map(CreatorPostComment::getCommentId).getContent();

        assertThat(ids).containsExactly(passedId, pendingId, blockedId, passAfterBlockOtherId, hiddenId);
    }

    @Test
    void 비로그인은_필터가_차단했거나_관리자가_숨긴_댓글을_보지_못한다() {
        var page = commentRepository.findVisibleByPostIdOldestFirst(postId, null, PageRequest.of(0, 20));

        assertThat(page.map(CreatorPostComment::getCommentId).getContent())
                .containsExactly(passedId, pendingId, passAfterBlockOtherId);
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    void 가려진_댓글은_작성자_본인에게만_보인다() {
        var seenByAuthor = commentRepository.findVisibleByPostIdOldestFirst(postId, authorId, PageRequest.of(0, 20));
        var seenByOther = commentRepository.findVisibleByPostIdOldestFirst(postId, otherId, PageRequest.of(0, 20));

        assertThat(seenByAuthor.map(CreatorPostComment::getCommentId).getContent())
                .containsExactly(passedId, pendingId, blockedId, passAfterBlockOtherId, hiddenId);
        assertThat(seenByOther.map(CreatorPostComment::getCommentId).getContent())
                .containsExactly(passedId, pendingId, passAfterBlockOtherId);
    }

    @Test
    void 가려진_댓글을_건너뛰어도_페이지가_채워지고_전체_개수가_맞는다() {
        var firstPage = commentRepository.findVisibleByPostIdOldestFirst(postId, null, PageRequest.of(0, 2));
        var secondPage = commentRepository.findVisibleByPostIdOldestFirst(postId, null, PageRequest.of(1, 2));

        assertThat(firstPage.map(CreatorPostComment::getCommentId).getContent()).containsExactly(passedId, pendingId);
        assertThat(secondPage.map(CreatorPostComment::getCommentId).getContent()).containsExactly(passAfterBlockOtherId);
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
    }

    @Test
    void 댓글_수도_조회자에게_보이는_댓글만_센다() {
        assertThat(commentRepository.countByPostIds(List.of(postId)))
                .extracting(PostCount::getPostId, PostCount::getTotal).containsExactly(tuple(postId, 5L));
        assertThat(commentRepository.countVisibleByPostIds(List.of(postId), null))
                .extracting(PostCount::getPostId, PostCount::getTotal).containsExactly(tuple(postId, 3L));
        assertThat(commentRepository.countVisibleByPostIds(List.of(postId), otherId))
                .extracting(PostCount::getPostId, PostCount::getTotal).containsExactly(tuple(postId, 3L));
        assertThat(commentRepository.countVisibleByPostIds(List.of(postId), authorId))
                .extracting(PostCount::getPostId, PostCount::getTotal).containsExactly(tuple(postId, 5L));
    }

    private CreatorPostComment comment(Long memberId, Instant createdAt) {
        return new CreatorPostComment(postId, memberId, "댓글", createdAt);
    }
}
