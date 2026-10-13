package kr.co.cking.post.repository;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.post.domain.CreatorPost;
import kr.co.cking.post.domain.PostVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * 실제 MySQL과 Connector/J 기본 설정에서 게시글 좋아요 쿼리를 확인한다(이슈 #554).
 *
 * <p>특히 이미 있는 좋아요에 {@code insertIfAbsent}를 다시 실행해도 생성 시각이 덮어써지지 않아야 한다. 새 좋아요인지는
 * insert의 영향 행 수(Connector/J 기본 설정은 중복 insert에도 1)가 아니라 저장된 생성 시각으로 판단하기 때문이다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class CreatorPostLikeRepositoryTest {

    private static final Instant FIRST = Instant.parse("2026-10-12T23:59:59.123456Z");
    private static final Instant NEXT_DAY = Instant.parse("2026-10-13T00:00:01.654321Z");

    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private CreatorRepository creatorRepository;
    @Autowired
    private CreatorPostRepository postRepository;
    @Autowired
    private CreatorPostLikeRepository likeRepository;

    private Long memberId;
    private Long otherMemberId;
    private Long postId;
    private Long otherPostId;

    @BeforeEach
    void setUp() {
        Member owner = memberRepository.saveAndFlush(new Member("좋아요 테스트 Creator", null, null, MemberRole.USER));
        Member member = memberRepository.saveAndFlush(new Member("좋아요 테스트 회원", null, null, MemberRole.USER));
        Member other = memberRepository.saveAndFlush(new Member("좋아요 테스트 다른 회원", null, null, MemberRole.USER));
        Creator creator = creatorRepository.saveAndFlush(new Creator(owner.getMemberId(), "좋아요 테스트 Creator"));
        memberId = member.getMemberId();
        otherMemberId = other.getMemberId();
        postId = postRepository.saveAndFlush(
                new CreatorPost(creator.getCreatorId(), "본문 1", PostVisibility.PUBLIC, FIRST)).getPostId();
        otherPostId = postRepository.saveAndFlush(
                new CreatorPost(creator.getCreatorId(), "본문 2", PostVisibility.PUBLIC, FIRST)).getPostId();
    }

    @Test
    void 같은_좋아요를_다시_넣어도_행은_하나이고_처음_생성_시각이_유지된다() {
        likeRepository.insertIfAbsent(postId, memberId, FIRST);
        likeRepository.insertIfAbsent(postId, memberId, NEXT_DAY);

        assertThat(likeRepository.countByPostId(postId)).isEqualTo(1);
        assertThat(likeRepository.findCreatedAt(postId, memberId)).contains(FIRST);
    }

    @Test
    void 좋아요가_없으면_생성_시각이_비어_있다() {
        assertThat(likeRepository.findCreatedAt(postId, memberId)).isEmpty();
    }

    @Test
    void 취소하면_지워지고_다시_누르면_새_생성_시각이_된다() {
        likeRepository.insertIfAbsent(postId, memberId, FIRST);

        assertThat(likeRepository.deleteByPostIdAndMemberId(postId, memberId)).isEqualTo(1);
        assertThat(likeRepository.deleteByPostIdAndMemberId(postId, memberId)).isZero();
        likeRepository.insertIfAbsent(postId, memberId, NEXT_DAY);

        assertThat(likeRepository.findCreatedAt(postId, memberId)).contains(NEXT_DAY);
    }

    @Test
    void 한_페이지_게시글의_좋아요_수와_내_좋아요를_일괄_집계한다() {
        likeRepository.insertIfAbsent(postId, memberId, FIRST);
        likeRepository.insertIfAbsent(postId, otherMemberId, FIRST);
        likeRepository.insertIfAbsent(otherPostId, otherMemberId, FIRST);

        List<PostCount> counts = likeRepository.countByPostIds(List.of(postId, otherPostId));

        assertThat(counts).extracting(PostCount::getPostId, PostCount::getTotal)
                .containsExactlyInAnyOrder(
                        tuple(postId, 2L),
                        tuple(otherPostId, 1L));
        assertThat(likeRepository.findLikedPostIds(memberId, List.of(postId, otherPostId))).containsExactly(postId);
    }

    @Test
    void 게시글을_삭제하면_좋아요도_함께_지워진다() {
        likeRepository.insertIfAbsent(postId, memberId, FIRST);
        likeRepository.insertIfAbsent(postId, otherMemberId, FIRST);

        postRepository.deleteById(postId);
        postRepository.flush();

        assertThat(likeRepository.countByPostId(postId)).isZero();
    }
}
