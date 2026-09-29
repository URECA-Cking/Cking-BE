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
import kr.co.cking.post.domain.PostVisibility;
import kr.co.cking.post.repository.CreatorPostCommentRepository;
import kr.co.cking.post.repository.CreatorPostRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
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

    private Member member() {
        Member member = memberRepository.saveAndFlush(new Member("comment-" + suffix(), null, null, MemberRole.USER));
        members.add(member);
        return member;
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
