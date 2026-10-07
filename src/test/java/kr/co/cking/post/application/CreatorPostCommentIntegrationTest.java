package kr.co.cking.post.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.follow.application.CreatorFollowService;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.post.application.dto.CreatorPostFields;
import kr.co.cking.post.application.dto.CreatorPostView;
import kr.co.cking.post.domain.CommentFilterAction;
import kr.co.cking.post.domain.PostVisibility;
import kr.co.cking.post.filter.CommentFilterResult;
import kr.co.cking.post.filter.CommentFilterResultService;
import kr.co.cking.post.filter.CommentFilterRetryService;
import kr.co.cking.post.repository.CommentFilterRetryCandidate;
import kr.co.cking.post.repository.CreatorPostCommentRepository;
import kr.co.cking.post.repository.CreatorPostRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 MySQL에서 댓글 저장·조회와 게시글 삭제 시 댓글 삭제(FK)를 확인한다. */
@SpringBootTest
class CreatorPostCommentIntegrationTest {

    @Autowired
    private CreatorPostService postService;
    @Autowired
    private CreatorPostCommentService commentService;
    @Autowired
    private CreatorFollowService followService;
    @Autowired
    private CommentFilterRetryService retryService;
    @Autowired
    private CommentFilterResultService resultService;
    @Autowired
    private CreatorPostCommentRepository commentRepository;
    @Autowired
    private CreatorPostRepository postRepository;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private CreatorRepository creatorRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<Member> members = new ArrayList<>();
    private Creator creator;
    private Member fan;
    private Long publicPostId;

    @AfterEach
    void cleanUp() {
        LockingQuerySignal.clear();
        if (creator != null) {
            followService.unfollow(fan.getMemberId(), creator.getCreatorId());
            postRepository.findAll().stream()
                    .filter(post -> post.getCreatorId().equals(creator.getCreatorId()))
                    .forEach(post -> postService.delete(creator.getMemberId(), post.getPostId()));
            creatorRepository.delete(creator);
        }
        memberRepository.deleteAll(members);
    }

    @Test
    void 팔로워가_단_댓글은_게시글을_삭제하면_함께_삭제된다() {
        Member owner = member();
        fan = member();
        creator = creatorRepository.saveAndFlush(new Creator(owner.getMemberId(), "creator-" + suffix()));
        followService.follow(fan.getMemberId(), creator.getCreatorId());
        CreatorPostView post = postService.create(owner.getMemberId(),
                new CreatorPostFields("본문", PostVisibility.FOLLOWERS, List.of()));

        commentService.create(fan.getMemberId(), creator.getCreatorId(), post.postId(), "첫 댓글");
        commentService.create(owner.getMemberId(), creator.getCreatorId(), post.postId(), "답글");

        assertThat(commentService.findByPost(creator.getCreatorId(), post.postId(), fan.getMemberId(),
                PageRequest.of(0, 20)).getContent())
                .extracting(comment -> comment.content())
                .containsExactly("첫 댓글", "답글");

        postService.delete(owner.getMemberId(), post.postId());

        assertThat(commentRepository.findAll().stream()
                .filter(comment -> comment.getPostId().equals(post.postId()))).isEmpty();
        assertThat(postRepository.findById(post.postId())).isEmpty();
    }

    @Test
    void 게시글_삭제가_잠금을_잡은_동안의_댓글_작성은_기다렸다가_삭제_Commit_후_404다() throws Exception {
        Member owner = member();
        fan = member();
        creator = creatorRepository.saveAndFlush(new Creator(owner.getMemberId(), "creator-" + suffix()));
        followService.follow(fan.getMemberId(), creator.getCreatorId());
        Long postId = postService.create(owner.getMemberId(),
                new CreatorPostFields("본문", PostVisibility.PUBLIC, List.of())).postId();

        CountDownLatch deleteLocked = new CountDownLatch(1);
        CountDownLatch releaseDelete = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> deletion = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        postRepository.findByIdForUpdate(postId);
                        deleteLocked.countDown();
                        awaitQuietly(releaseDelete);
                        postService.delete(owner.getMemberId(), postId);
                    }));
            assertThat(deleteLocked.await(10, TimeUnit.SECONDS)).isTrue();

            // 댓글 요청이 게시글 공유 잠금 조회를 실행했고, 삭제 잠금 때문에 그 조회에서 막혀 있음을 확인한다.
            // 잠금 없는 일반 조회였다면 신호가 오지 않고, FK 확인 INSERT에서 막혀 결국 404가 아닌 오류가 된다.
            CountDownLatch shareLockQueried = LockingQuerySignal.expect("creator_post", "for share");
            Future<Throwable> commenting = executor.submit(() -> failureOf(() ->
                    commentService.create(fan.getMemberId(), creator.getCreatorId(), postId, "동시에 쓴 댓글")));
            assertBlockedAfter(shareLockQueried, commenting);

            releaseDelete.countDown();
            deletion.get(10, TimeUnit.SECONDS);

            assertNotFound(commenting.get(10, TimeUnit.SECONDS));
        } finally {
            releaseDelete.countDown();
            executor.shutdownNow();
        }
        assertThat(postRepository.findById(postId)).isEmpty();
    }

    @Test
    void 댓글_삭제가_잠금을_잡은_동안의_같은_댓글_수정은_기다렸다가_삭제_Commit_후_404다() throws Exception {
        Member owner = member();
        fan = member();
        creator = creatorRepository.saveAndFlush(new Creator(owner.getMemberId(), "creator-" + suffix()));
        followService.follow(fan.getMemberId(), creator.getCreatorId());
        Long postId = postService.create(owner.getMemberId(),
                new CreatorPostFields("본문", PostVisibility.PUBLIC, List.of())).postId();
        Long commentId = commentService.create(fan.getMemberId(), creator.getCreatorId(), postId, "원래 댓글")
                .commentId();

        CountDownLatch deleteDone = new CountDownLatch(1);
        CountDownLatch releaseDelete = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> deletion = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        commentService.delete(owner.getMemberId(), creator.getCreatorId(), postId, commentId);
                        deleteDone.countDown();
                        awaitQuietly(releaseDelete);
                    }));
            assertThat(deleteDone.await(10, TimeUnit.SECONDS)).isTrue();

            // 게시글 공유 잠금은 서로 충돌하지 않으므로, 수정 요청은 댓글 쓰기 잠금 조회에서 막혀야 한다.
            // 삭제 쪽의 같은 조회는 이미 끝났으므로 여기서 등록한 신호는 수정 요청만 연다.
            CountDownLatch commentLockQueried = LockingQuerySignal.expect("creator_post_comment", "for update");
            Future<Throwable> updating = executor.submit(() -> failureOf(() ->
                    commentService.update(fan.getMemberId(), creator.getCreatorId(), postId, commentId, "수정")));
            assertBlockedAfter(commentLockQueried, updating);

            releaseDelete.countDown();
            deletion.get(10, TimeUnit.SECONDS);

            assertNotFound(updating.get(10, TimeUnit.SECONDS));
        } finally {
            releaseDelete.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * 요청이 기대한 잠금 조회 SQL을 실행했고(신호), 그 뒤에도 끝나지 않고 막혀 있는지 확인한다. 신호는 스레드 시작이
     * 아니라 잠금 조회 진입을 뜻하므로, 이후 미완료는 그 조회가 상대 Transaction의 잠금에 막혔다는 의미다.
     */
    private void assertBlockedAfter(CountDownLatch lockQueried, Future<?> request) throws InterruptedException {
        assertThat(lockQueried.await(10, TimeUnit.SECONDS)).as("요청이 잠금 조회를 실행해야 한다").isTrue();
        Thread.sleep(300);
        assertThat(request.isDone()).as("상대 Transaction이 Commit될 때까지 잠금 조회에서 기다려야 한다").isFalse();
    }

    private Throwable failureOf(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private void assertNotFound(Throwable failure) {
        assertThat(failure).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    private void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void 재필터링_선점은_시도_횟수와_다음_시각을_확보하고_같은_조회_결과로는_두_번_성공하지_않는다() {
        Long commentId = commentOnPublicPost("재시도 대상");
        Instant farFuture = Instant.now().plus(Duration.ofDays(1));
        CommentFilterRetryCandidate candidate = retryCandidate(commentId, farFuture, farFuture, 5).orElseThrow();
        assertThat(candidate.attempts()).isZero();
        assertThat(candidate.nextAttemptAt()).isNull();

        Instant nextAttemptAt = Instant.now().plus(Duration.ofMinutes(1));
        assertThat(retryService.claim(candidate, nextAttemptAt)).isTrue();
        assertThat(retryService.claim(candidate, nextAttemptAt)).isFalse();

        // 다음 시도 시각 전에는 대상이 아니고, 시각이 지나면 횟수 1로 다시 대상이 된다.
        assertThat(retryCandidate(commentId, Instant.now(), farFuture, 5)).isEmpty();
        assertThat(retryCandidate(commentId, nextAttemptAt.plusSeconds(1), farFuture, 5))
                .hasValueSatisfying(again -> assertThat(again.attempts()).isEqualTo(1));
    }

    @Test
    void 재필터링은_작성_직후_대기_시간이_지나기_전과_시도_상한에_닿은_댓글을_제외한다() {
        Long commentId = commentOnPublicPost("대기 중");
        Instant farFuture = Instant.now().plus(Duration.ofDays(1));

        // 방금 쓴 댓글은 대기 시간(pendingBefore)이 지나기 전에는 대상이 아니다.
        assertThat(retryCandidate(commentId, Instant.now(), Instant.now().minus(Duration.ofHours(1)), 5)).isEmpty();

        CommentFilterRetryCandidate candidate = retryCandidate(commentId, farFuture, farFuture, 5).orElseThrow();
        assertThat(retryService.claim(candidate, Instant.now().minusSeconds(1))).isTrue();
        assertThat(retryCandidate(commentId, farFuture, farFuture, 1)).isEmpty();
        assertThat(retryCandidate(commentId, farFuture, farFuture, 2)).isPresent();
    }

    @Test
    void 판정을_마친_댓글은_재필터링_대상도_선점_대상도_아니다() {
        Long commentId = commentOnPublicPost("판정 완료");
        Instant farFuture = Instant.now().plus(Duration.ofDays(1));
        CommentFilterRetryCandidate candidate = retryCandidate(commentId, farFuture, farFuture, 5).orElseThrow();

        assertThat(resultService.saveResult(commentId, "판정 완료", new CommentFilterResult(
                CommentFilterAction.PASS, List.of(), "rule-1", "model-1"))).isTrue();

        assertThat(retryCandidate(commentId, farFuture, farFuture, 5)).isEmpty();
        assertThat(retryService.claim(candidate, farFuture)).isFalse();
    }

    @Test
    void 후보를_조회한_뒤_본문이_수정되면_횟수가_같은_0이어도_선점하지_못한다() {
        Long commentId = commentOnPublicPost("고치기 전");
        Instant farFuture = Instant.now().plus(Duration.ofDays(1));
        CommentFilterRetryCandidate candidate = retryCandidate(commentId, farFuture, farFuture, 5).orElseThrow();
        assertThat(candidate.attempts()).isZero();

        commentService.update(fan.getMemberId(), creator.getCreatorId(), publicPostId, commentId, "고친 뒤");

        // 수정으로 횟수가 다시 0이 되어 횟수만 비교하면 새 본문을 대기 시간 없이 선점하게 된다.
        assertThat(retryService.claim(candidate, farFuture)).isFalse();
        assertThat(retryCandidate(commentId, farFuture, farFuture, 5))
                .hasValueSatisfying(fresh -> assertThat(fresh.attempts()).isZero());
    }

    @Test
    void 재제출을_선점한_뒤_본문이_수정되면_횟수가_초기화되어_이전_횟수로는_선점할_수_없다() {
        Long commentId = commentOnPublicPost("고치기 전");
        Instant farFuture = Instant.now().plus(Duration.ofDays(1));
        CommentFilterRetryCandidate candidate = retryCandidate(commentId, farFuture, farFuture, 5).orElseThrow();
        assertThat(retryService.claim(candidate, Instant.now().minusSeconds(1))).isTrue();
        CommentFilterRetryCandidate second = retryCandidate(commentId, farFuture, farFuture, 5).orElseThrow();
        assertThat(second.attempts()).isEqualTo(1);

        commentService.update(fan.getMemberId(), creator.getCreatorId(), publicPostId, commentId, "고친 뒤");

        assertThat(retryService.claim(second, farFuture)).isFalse();
    }

    @Test
    void 선점을_되돌리면_시도_횟수와_다음_시각이_확보_전으로_돌아가_곧바로_다시_대상이_된다() {
        Long commentId = commentOnPublicPost("큐가 가득 참");
        Instant farFuture = Instant.now().plus(Duration.ofDays(1));
        CommentFilterRetryCandidate candidate = retryCandidate(commentId, farFuture, farFuture, 5).orElseThrow();
        assertThat(retryService.claim(candidate, Instant.now().plus(Duration.ofMinutes(30)))).isTrue();
        assertThat(retryCandidate(commentId, Instant.now(), farFuture, 5)).isEmpty();

        assertThat(retryService.release(candidate)).isTrue();

        assertThat(retryCandidate(commentId, Instant.now(), farFuture, 5))
                .hasValueSatisfying(restored -> {
                    assertThat(restored.attempts()).isZero();
                    assertThat(restored.nextAttemptAt()).isNull();
                });
        // 이미 되돌린 선점은 다시 되돌려지지 않는다(횟수가 음수가 되지 않는다).
        assertThat(retryService.release(candidate)).isFalse();
    }

    @Test
    void 재시도한_댓글의_선점을_되돌리면_이전_다음_시각으로_복원된다() {
        Long commentId = commentOnPublicPost("두 번째 시도");
        Instant farFuture = Instant.now().plus(Duration.ofDays(1));
        Instant firstNext = Instant.now().minusSeconds(1);
        assertThat(retryService.claim(retryCandidate(commentId, farFuture, farFuture, 5).orElseThrow(), firstNext))
                .isTrue();
        CommentFilterRetryCandidate second = retryCandidate(commentId, Instant.now(), farFuture, 5).orElseThrow();
        assertThat(second.attempts()).isEqualTo(1);
        assertThat(retryService.claim(second, Instant.now().plus(Duration.ofHours(1)))).isTrue();

        assertThat(retryService.release(second)).isTrue();

        assertThat(retryCandidate(commentId, Instant.now(), farFuture, 5))
                .hasValueSatisfying(restored -> assertThat(restored.attempts()).isEqualTo(1));
    }

    @Test
    void 선점을_되돌릴_때_본문이_수정되어_횟수가_초기화됐으면_되돌리지_않는다() {
        Long commentId = commentOnPublicPost("고치기 전");
        Instant farFuture = Instant.now().plus(Duration.ofDays(1));
        CommentFilterRetryCandidate candidate = retryCandidate(commentId, farFuture, farFuture, 5).orElseThrow();
        assertThat(retryService.claim(candidate, Instant.now().plus(Duration.ofMinutes(30)))).isTrue();

        commentService.update(fan.getMemberId(), creator.getCreatorId(), publicPostId, commentId, "고친 뒤");

        assertThat(retryService.release(candidate)).isFalse();
        assertThat(retryCandidate(commentId, farFuture, farFuture, 5))
                .hasValueSatisfying(fresh -> assertThat(fresh.attempts()).isZero());
    }

    private Long commentOnPublicPost(String content) {
        Member owner = member();
        fan = member();
        creator = creatorRepository.saveAndFlush(new Creator(owner.getMemberId(), "creator-" + suffix()));
        followService.follow(fan.getMemberId(), creator.getCreatorId());
        publicPostId = postService.create(owner.getMemberId(),
                new CreatorPostFields("본문", PostVisibility.PUBLIC, List.of())).postId();
        return commentService.create(fan.getMemberId(), creator.getCreatorId(), publicPostId, content).commentId();
    }

    /** 공용 DB의 다른 댓글을 섞지 않도록 대상 댓글의 후보만 골라 돌려준다. */
    private Optional<CommentFilterRetryCandidate> retryCandidate(
            Long commentId, Instant now, Instant pendingBefore, int maxAttempts) {
        return retryService.findCandidates(now, pendingBefore, maxAttempts, 1000).stream()
                .filter(candidate -> candidate.commentId().equals(commentId))
                .findFirst();
    }

    private Member member() {
        Member member = memberRepository.saveAndFlush(new Member("comment-" + suffix(), null, null, MemberRole.USER));
        members.add(member);
        return member;
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
