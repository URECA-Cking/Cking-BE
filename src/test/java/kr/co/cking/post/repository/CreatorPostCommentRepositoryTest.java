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
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/** 실제 MySQL에서 관리자 필터 차단 목록의 조건과 정렬을 확인한다(이슈 #809). */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class CreatorPostCommentRepositoryTest {

    /** 공유 DB에 남은 다른 판정보다 앞서도록 먼 미래 시각을 쓴다. */
    private static final Instant FILTERED_AT = Instant.parse("2099-01-01T00:00:00.123456Z");

    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private CreatorRepository creatorRepository;
    @Autowired
    private CreatorPostRepository postRepository;
    @Autowired
    private CreatorPostCommentRepository commentRepository;

    private Long memberId;
    private Long postId;

    @BeforeEach
    void setUp() {
        Member owner = memberRepository.saveAndFlush(new Member("필터 테스트 Creator", null, null, MemberRole.USER));
        Member member = memberRepository.saveAndFlush(new Member("필터 테스트 회원", null, null, MemberRole.USER));
        Creator creator = creatorRepository.saveAndFlush(new Creator(owner.getMemberId(), "필터 테스트 Creator"));
        memberId = member.getMemberId();
        postId = postRepository.saveAndFlush(
                new CreatorPost(creator.getCreatorId(), "본문", PostVisibility.PUBLIC, FILTERED_AT)).getPostId();
    }

    @Test
    void 차단_댓글만_최근_판정_순으로_읽고_같은_시각이면_ID가_큰_댓글이_먼저다() {
        Long older = judged(CommentFilterAction.BLOCK, FILTERED_AT);
        Long sameTimeFirst = judged(CommentFilterAction.BLOCK, FILTERED_AT.plusSeconds(10));
        Long sameTimeSecond = judged(CommentFilterAction.BLOCK, FILTERED_AT.plusSeconds(10));
        Long passed = judged(CommentFilterAction.PASS, FILTERED_AT.plusSeconds(20));

        List<Long> ids = commentRepository
                .findByFilterActionLatestFirst(CommentFilterAction.BLOCK, PageRequest.of(0, 100)).stream()
                .map(CreatorPostComment::getCommentId)
                .toList();

        assertThat(ids).doesNotContain(passed);
        assertThat(ids).containsSubsequence(sameTimeSecond, sameTimeFirst, older);
    }

    private Long judged(CommentFilterAction action, Instant filteredAt) {
        CreatorPostComment comment = new CreatorPostComment(postId, memberId, "댓글", FILTERED_AT);
        comment.markFiltered(action, List.of("model"), "rule-1", "model-1", filteredAt);
        return commentRepository.saveAndFlush(comment).getCommentId();
    }
}
