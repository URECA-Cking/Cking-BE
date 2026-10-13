package kr.co.cking.post.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.follow.application.CreatorFollowService;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberNickname;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.post.application.dto.AdminCommentReportActionView;
import kr.co.cking.post.application.dto.AdminCommentReportView;
import kr.co.cking.post.application.dto.CreatorPostCommentView;
import kr.co.cking.post.application.dto.CommentReportView;
import kr.co.cking.post.application.dto.CreatorPostFields;
import kr.co.cking.post.domain.CommentReportReason;
import kr.co.cking.post.domain.CommentReportStatus;
import kr.co.cking.post.domain.CreatorPostCommentReport;
import kr.co.cking.post.domain.PostErrorCode;
import kr.co.cking.post.domain.PostVisibility;
import kr.co.cking.post.repository.CreatorPostCommentReportRepository;
import kr.co.cking.post.repository.CreatorPostCommentRepository;
import kr.co.cking.post.repository.CreatorPostRepository;
import kr.co.cking.sanction.domain.SanctionErrorCode;
import kr.co.cking.sanction.repository.MemberSanctionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 MySQL에서 댓글 신고 저장, 중복 방지, 삭제 연쇄(FK), 관리자 집계 쿼리를 확인한다. */
@SpringBootTest
class CreatorPostCommentReportIntegrationTest {

    @Autowired
    private CreatorPostService postService;
    @Autowired
    private CreatorPostCommentService commentService;
    @Autowired
    private CreatorPostCommentReportService reportService;
    @Autowired
    private AdminCommentReportQueryService adminQueryService;
    @Autowired
    private AdminCommentReportCommandService adminCommandService;
    @Autowired
    private CreatorFollowService followService;
    @Autowired
    private CreatorPostCommentReportRepository reportRepository;
    @Autowired
    private CreatorPostCommentRepository commentRepository;
    @Autowired
    private CreatorPostRepository postRepository;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private CreatorRepository creatorRepository;
    @Autowired
    private MemberSanctionRepository sanctionRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<Member> members = new ArrayList<>();
    private Creator creator;
    private Member author;
    private Long postId;

    @AfterEach
    void cleanUp() {
        if (creator != null) {
            followService.unfollow(author.getMemberId(), creator.getCreatorId());
            postRepository.findAll().stream()
                    .filter(post -> post.getCreatorId().equals(creator.getCreatorId()))
                    .forEach(post -> postService.delete(creator.getMemberId(), post.getPostId()));
            creatorRepository.delete(creator);
        }
        // 관리자가 댓글을 숨기면 작성자에게 제재가 생기고 회원 FK(대상·처리 관리자)가 걸리므로 회원보다 먼저 지운다.
        List<Long> memberIds = members.stream().map(Member::getMemberId).toList();
        sanctionRepository.deleteAll(sanctionRepository.findAll().stream()
                .filter(sanction -> memberIds.contains(sanction.getMemberId())
                        || memberIds.contains(sanction.getCreatedBy())
                        || memberIds.contains(sanction.getReleasedBy()))
                .toList());
        memberRepository.deleteAll(members);
    }

    @Test
    void 신고를_저장하고_같은_신고자의_중복_신고는_기존_신고를_돌려준다() {
        Long commentId = commentOnPublicPost();
        Member reporter = member(MemberRole.USER);

        CommentReportView first = reportService.report(
                reporter.getMemberId(), creator.getCreatorId(), postId, commentId, CommentReportReason.ABUSE, null);
        CommentReportView again = reportService.report(
                reporter.getMemberId(), creator.getCreatorId(), postId, commentId, CommentReportReason.SPAM, null);

        assertThat(again.reportId()).isEqualTo(first.reportId());
        assertThat(again.reason()).isEqualTo(CommentReportReason.ABUSE);
        assertThat(reportRepository.findAll().stream()
                .filter(report -> report.getCommentId().equals(commentId))).hasSize(1);
    }

    @Test
    void 같은_신고자와_댓글_조합은_DB_유니크_제약으로도_막힌다() {
        Long commentId = commentOnPublicPost();
        Member reporter = member(MemberRole.USER);
        reportRepository.saveAndFlush(new CreatorPostCommentReport(
                commentId, reporter.getMemberId(), CommentReportReason.ABUSE, null, Instant.now()));

        assertThatThrownBy(() -> reportRepository.saveAndFlush(new CreatorPostCommentReport(
                commentId, reporter.getMemberId(), CommentReportReason.SPAM, null, Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 본인_댓글과_없는_댓글은_신고할_수_없다() {
        Long commentId = commentOnPublicPost();

        assertThatThrownBy(() -> reportService.report(
                author.getMemberId(), creator.getCreatorId(), postId, commentId, CommentReportReason.ABUSE, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> reportService.report(
                member(MemberRole.USER).getMemberId(), creator.getCreatorId(), postId, Long.MAX_VALUE,
                CommentReportReason.ABUSE, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void 댓글을_삭제하면_그_댓글의_신고도_함께_삭제된다() {
        Long commentId = commentOnPublicPost();
        Member reporter = member(MemberRole.USER);
        reportService.report(
                reporter.getMemberId(), creator.getCreatorId(), postId, commentId, CommentReportReason.ABUSE, null);

        commentService.delete(author.getMemberId(), creator.getCreatorId(), postId, commentId);

        assertThat(reportRepository.findByCommentIdAndReporterMemberId(commentId, reporter.getMemberId())).isEmpty();
    }

    @Test
    void 게시글을_삭제하면_댓글과_신고가_함께_삭제된다() {
        Long commentId = commentOnPublicPost();
        Member reporter = member(MemberRole.USER);
        reportService.report(
                reporter.getMemberId(), creator.getCreatorId(), postId, commentId, CommentReportReason.SPAM, null);

        postService.delete(creator.getMemberId(), postId);

        assertThat(commentRepository.findById(commentId)).isEmpty();
        assertThat(reportRepository.findByCommentIdAndReporterMemberId(commentId, reporter.getMemberId())).isEmpty();
    }

    @Test
    void 관리자_목록은_댓글별로_신고_수와_사유별_수를_최근_신고_순으로_모은다() {
        Long first = commentOnPublicPost();
        Long second = commentService.create(author.getMemberId(), creator.getCreatorId(), postId, "두 번째").commentId();
        Member reporterA = member(MemberRole.USER);
        Member reporterB = member(MemberRole.USER);
        Member admin = member(MemberRole.ADMIN);

        reportService.report(reporterA.getMemberId(), creator.getCreatorId(), postId, first,
                CommentReportReason.ABUSE, null);
        reportService.report(reporterB.getMemberId(), creator.getCreatorId(), postId, first,
                CommentReportReason.ABUSE, null);
        reportService.report(reporterA.getMemberId(), creator.getCreatorId(), postId, second,
                CommentReportReason.OTHER, "위협으로 느껴집니다");

        List<AdminCommentReportView> views = adminQueryService.findReportedComments(
                admin.getMemberId(), null, PageRequest.of(0, 100)).getContent().stream()
                .filter(view -> view.commentId().equals(first) || view.commentId().equals(second))
                .toList();

        // 두 번째 댓글이 더 나중에 신고되었으므로 먼저 나온다.
        assertThat(views).extracting(AdminCommentReportView::commentId).containsExactly(second, first);
        assertThat(views.get(0).reportCount()).isEqualTo(1);
        assertThat(views.get(0).reasonCounts()).containsEntry(CommentReportReason.OTHER, 1L);
        assertThat(views.get(0).content()).isEqualTo("두 번째");
        assertThat(views.get(1).reportCount()).isEqualTo(2);
        assertThat(views.get(1).reasonCounts()).containsEntry(CommentReportReason.ABUSE, 2L);
        assertThat(views.get(1).creatorId()).isEqualTo(creator.getCreatorId());
        assertThat(views.get(1).authorMemberId()).isEqualTo(author.getMemberId());
    }

    @Test
    void 관리자가_아니면_신고_목록을_조회할_수_없다() {
        Member user = member(MemberRole.USER);

        assertThatThrownBy(() -> adminQueryService.findReportedComments(user.getMemberId(), null, PageRequest.of(0, 20)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.FORBIDDEN));
    }

    @Test
    void 같은_사용자가_서로_다른_댓글을_동시에_신고해도_시간당_한도를_넘지_않는다() throws Exception {
        Long firstCommentId = commentOnPublicPost();
        List<Long> commentIds = new ArrayList<>(List.of(firstCommentId));
        for (int i = 1; i <= CreatorPostCommentReportService.REPORT_LIMIT + 1; i++) {
            commentIds.add(commentService.create(
                    author.getMemberId(), creator.getCreatorId(), postId, "댓글 " + i).commentId());
        }
        Member reporter = member(MemberRole.USER);
        // 한도(20)까지 한 건을 남기고 채운다. 이 시점의 기존 신고 수는 19건이다.
        for (int i = 0; i < CreatorPostCommentReportService.REPORT_LIMIT - 1; i++) {
            reportService.report(reporter.getMemberId(), creator.getCreatorId(), postId, commentIds.get(i),
                    CommentReportReason.ABUSE, null);
        }

        // 서로 다른 두 댓글을 동시에 신고한다. 댓글별 잠금만으로는 두 요청이 같은 기존 수(19)를 읽고 모두 저장해 21건이 된다.
        Long lastTwoA = commentIds.get(CreatorPostCommentReportService.REPORT_LIMIT - 1);
        Long lastTwoB = commentIds.get(CreatorPostCommentReportService.REPORT_LIMIT);
        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> first = executor.submit(() -> failureOfConcurrentReport(
                    startTogether, reporter, lastTwoA));
            Future<Throwable> second = executor.submit(() -> failureOfConcurrentReport(
                    startTogether, reporter, lastTwoB));
            List<Throwable> failures = new ArrayList<>();
            failures.add(first.get(30, TimeUnit.SECONDS));
            failures.add(second.get(30, TimeUnit.SECONDS));

            assertThat(failures.stream().filter(failure -> failure == null)).hasSize(1);
            assertThat(failures.stream().filter(failure -> failure != null)).singleElement()
                    .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
                            .isEqualTo(PostErrorCode.COMMENT_REPORT_LIMIT_EXCEEDED));
        } finally {
            executor.shutdownNow();
        }

        assertThat(reportRepository.countByReporterMemberIdAndCreatedAtGreaterThanEqual(
                reporter.getMemberId(), Instant.EPOCH)).isEqualTo(CreatorPostCommentReportService.REPORT_LIMIT);
    }

    @Test
    void 신고는_접수로_저장되고_처리_정보는_비어_있다() {
        Long commentId = commentOnPublicPost();
        Member reporter = member(MemberRole.USER);

        reportService.report(
                reporter.getMemberId(), creator.getCreatorId(), postId, commentId, CommentReportReason.ABUSE, null);

        CreatorPostCommentReport saved = reportRepository
                .findByCommentIdAndReporterMemberId(commentId, reporter.getMemberId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(CommentReportStatus.PENDING);
        assertThat(saved.getHandledBy()).isNull();
        assertThat(saved.getHandledAt()).isNull();
    }

    @Test
    void 숨기면_접수_신고가_처리_완료가_되고_사용자에게는_가려지며_원문_보기도_막히고_새_신고도_막힌다() {
        Long commentId = commentOnPublicPost();
        Member reporterA = member(MemberRole.USER);
        Member reporterB = member(MemberRole.USER);
        Member stranger = member(MemberRole.USER);
        Member admin = member(MemberRole.ADMIN);
        reportService.report(reporterA.getMemberId(), creator.getCreatorId(), postId, commentId,
                CommentReportReason.ABUSE, null);
        reportService.report(reporterB.getMemberId(), creator.getCreatorId(), postId, commentId,
                CommentReportReason.SPAM, null);

        AdminCommentReportActionView hidden = adminCommandService.hide(admin.getMemberId(), commentId);

        assertThat(hidden.status()).isEqualTo(CommentReportStatus.RESOLVED);
        assertThat(hidden.handledReportCount()).isEqualTo(2);
        CreatorPostCommentReport handled = reportRepository
                .findByCommentIdAndReporterMemberId(commentId, reporterA.getMemberId()).orElseThrow();
        assertThat(handled.getStatus()).isEqualTo(CommentReportStatus.RESOLVED);
        assertThat(handled.getHandledBy()).isEqualTo(admin.getMemberId());
        assertThat(handled.getHandledAt()).isNotNull();

        // 관리자 목록: 접수 필터에서는 빠지고 처리 완료 필터에 나온다.
        assertThat(reportedIds(admin, CommentReportStatus.PENDING)).doesNotContain(commentId);
        AdminCommentReportView row = adminQueryService.findReportedComments(
                        admin.getMemberId(), CommentReportStatus.RESOLVED, PageRequest.of(0, 100)).getContent().stream()
                .filter(view -> view.commentId().equals(commentId)).findFirst().orElseThrow();
        assertThat(row.status()).isEqualTo(CommentReportStatus.RESOLVED);
        assertThat(row.hiddenByAdmin()).isTrue();
        assertThat(row.reportCount()).isEqualTo(2);
        assertThat(row.pendingReportCount()).isZero();
        assertThat(row.content()).isEqualTo("신고될 댓글");

        // 사용자 목록: 다른 사람에게는 가려지고 원문 보기가 허용되지 않으며 작성자 본인에게는 그대로 보인다.
        CreatorPostCommentView seenByStranger = commentService.findByPost(
                creator.getCreatorId(), postId, stranger.getMemberId(), PageRequest.of(0, 50)).getContent().get(0);
        assertThat(seenByStranger.filtered()).isTrue();
        assertThat(seenByStranger.revealable()).isFalse();
        assertThat(seenByStranger.content()).isNull();
        CreatorPostCommentView seenByAuthor = commentService.findByPost(
                creator.getCreatorId(), postId, author.getMemberId(), PageRequest.of(0, 50)).getContent().get(0);
        assertThat(seenByAuthor.filtered()).isFalse();
        assertThat(seenByAuthor.content()).isEqualTo("신고될 댓글");
        assertThatThrownBy(() -> commentService.findOriginal(
                creator.getCreatorId(), postId, commentId, stranger.getMemberId()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(PostErrorCode.COMMENT_NOT_REVEALABLE));

        // 숨긴 댓글에는 새 신고를 받지 않고, 같은 조치는 멱등이며, 다른 조치는 충돌한다.
        assertThatThrownBy(() -> reportService.report(stranger.getMemberId(), creator.getCreatorId(), postId,
                commentId, CommentReportReason.ABUSE, null))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(PostErrorCode.COMMENT_REPORT_ALREADY_FILTERED));
        assertThat(adminCommandService.hide(admin.getMemberId(), commentId).handledReportCount()).isZero();
        assertThatThrownBy(() -> adminCommandService.reject(admin.getMemberId(), commentId))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(PostErrorCode.COMMENT_REPORT_ALREADY_HANDLED));
    }

    @Test
    void 반려하면_댓글은_보이고_이후_새_신고는_다시_접수된다() {
        Long commentId = commentOnPublicPost();
        Member reporterA = member(MemberRole.USER);
        Member reporterB = member(MemberRole.USER);
        Member stranger = member(MemberRole.USER);
        Member admin = member(MemberRole.ADMIN);
        reportService.report(reporterA.getMemberId(), creator.getCreatorId(), postId, commentId,
                CommentReportReason.SPAM, null);

        AdminCommentReportActionView rejected = adminCommandService.reject(admin.getMemberId(), commentId);

        assertThat(rejected.status()).isEqualTo(CommentReportStatus.REJECTED);
        assertThat(rejected.handledReportCount()).isEqualTo(1);
        assertThat(reportedIds(admin, CommentReportStatus.REJECTED)).contains(commentId);
        assertThat(reportedIds(admin, CommentReportStatus.PENDING)).doesNotContain(commentId);
        assertThat(commentService.findByPost(creator.getCreatorId(), postId, stranger.getMemberId(),
                PageRequest.of(0, 50)).getContent().get(0).filtered()).isFalse();
        // 반려한 댓글은 다시 반려해도 성공하지만 접수 신고가 없어 숨길 수는 없다.
        assertThat(adminCommandService.reject(admin.getMemberId(), commentId).handledReportCount()).isZero();
        assertThatThrownBy(() -> adminCommandService.hide(admin.getMemberId(), commentId))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(PostErrorCode.COMMENT_REPORT_ALREADY_HANDLED));

        // 반려 뒤 들어온 새 신고는 접수로 쌓이고, 이때는 숨길 수 있다(전에 반려한 신고는 그대로 둔다).
        reportService.report(reporterB.getMemberId(), creator.getCreatorId(), postId, commentId,
                CommentReportReason.ABUSE, null);
        assertThat(reportedIds(admin, CommentReportStatus.PENDING)).contains(commentId);
        assertThat(adminCommandService.hide(admin.getMemberId(), commentId).handledReportCount()).isEqualTo(1);
        assertThat(reportRepository.findByCommentIdAndReporterMemberId(commentId, reporterA.getMemberId())
                .orElseThrow().getStatus()).isEqualTo(CommentReportStatus.REJECTED);
        assertThat(reportedIds(admin, CommentReportStatus.RESOLVED)).contains(commentId);
    }

    @Test
    void 신고가_없는_댓글과_관리자가_아닌_호출자의_조치는_거부한다() {
        Long commentId = commentOnPublicPost();
        Member user = member(MemberRole.USER);
        Member admin = member(MemberRole.ADMIN);

        assertThatThrownBy(() -> adminCommandService.hide(admin.getMemberId(), commentId))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> adminCommandService.reject(admin.getMemberId(), Long.MAX_VALUE))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> adminCommandService.hide(user.getMemberId(), commentId))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.FORBIDDEN));
    }

    @Test
    void 숨김과_새_신고가_동시에_와도_숨긴_댓글에_접수_신고가_남지_않는다() throws Exception {
        Long commentId = commentOnPublicPost();
        Member firstReporter = member(MemberRole.USER);
        Member lateReporter = member(MemberRole.USER);
        Member admin = member(MemberRole.ADMIN);
        reportService.report(firstReporter.getMemberId(), creator.getCreatorId(), postId, commentId,
                CommentReportReason.ABUSE, null);

        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> hide = executor.submit(() -> {
                startTogether.await(10, TimeUnit.SECONDS);
                try {
                    adminCommandService.hide(admin.getMemberId(), commentId);
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
            Future<Throwable> report = executor.submit(() -> {
                startTogether.await(10, TimeUnit.SECONDS);
                try {
                    reportService.report(lateReporter.getMemberId(), creator.getCreatorId(), postId, commentId,
                            CommentReportReason.SPAM, null);
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
            assertThat(hide.get(30, TimeUnit.SECONDS)).isNull();
            Throwable reportFailure = report.get(30, TimeUnit.SECONDS);
            // 신고가 먼저면 숨김이 함께 처리하고, 숨김이 먼저면 새 신고는 거부된다. 어느 쪽이든 접수 신고가 남지 않는다.
            if (reportFailure != null) {
                assertThat(reportFailure).isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(PostErrorCode.COMMENT_REPORT_ALREADY_FILTERED));
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(commentRepository.findById(commentId).orElseThrow().isHiddenByAdmin()).isTrue();
        assertThat(reportRepository.findAll().stream()
                .filter(saved -> saved.getCommentId().equals(commentId))
                .filter(saved -> saved.getStatus() == CommentReportStatus.PENDING)).isEmpty();
    }

    /**
     * 관리자의 숨김이 커밋되기 전에 시작한 작성자의 댓글 작성은 작성자 행 잠금에서 기다렸다가, 숨김 커밋 뒤의 제재를 보고
     * 거부돼야 한다. 잠금 없이 일반 조회로 시작하면 숨김 커밋 전 스냅샷에 묶여 제한을 못 보고 댓글이 저장된다.
     */
    @Test
    void 관리자가_숨기는_중에_시작한_작성은_숨김이_끝난_뒤_제한을_보고_거부된다() throws Exception {
        Long commentId = commentOnPublicPost();
        Member reporter = member(MemberRole.USER);
        Member admin = member(MemberRole.ADMIN);
        reportService.report(reporter.getMemberId(), creator.getCreatorId(), postId, commentId,
                CommentReportReason.ABUSE, null);
        long commentsBefore = commentRepository.findAll().stream().filter(c -> c.getPostId().equals(postId)).count();

        CountDownLatch hiddenButNotCommitted = new CountDownLatch(1);
        CountDownLatch mayCommit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> hideTransaction = executor.submit(() ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        adminCommandService.hide(admin.getMemberId(), commentId);
                        hiddenButNotCommitted.countDown();
                        try {
                            mayCommit.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }));
            assertThat(hiddenButNotCommitted.await(30, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> write = executor.submit(() -> {
                try {
                    commentService.create(author.getMemberId(), creator.getCreatorId(), postId, "제한 직전에 쓴 댓글");
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
            // 작성자 행 잠금을 숨김 트랜잭션이 쥐고 있으므로 작성은 끝나지 않고 기다려야 한다.
            Thread.sleep(700);
            assertThat(write.isDone()).isFalse();

            mayCommit.countDown();
            hideTransaction.get(30, TimeUnit.SECONDS);
            assertThat(write.get(30, TimeUnit.SECONDS)).isInstanceOfSatisfying(BusinessException.class,
                    exception -> assertThat(exception.getErrorCode()).isEqualTo(SanctionErrorCode.COMMENT_RESTRICTED));
        } finally {
            mayCommit.countDown();
            executor.shutdownNow();
        }

        assertThat(commentRepository.findAll().stream().filter(c -> c.getPostId().equals(postId)).count())
                .isEqualTo(commentsBefore);
    }

    private List<Long> reportedIds(Member admin, CommentReportStatus status) {
        return adminQueryService.findReportedComments(admin.getMemberId(), status, PageRequest.of(0, 100))
                .getContent().stream().map(AdminCommentReportView::commentId).toList();
    }

    private Throwable failureOfConcurrentReport(CyclicBarrier startTogether, Member reporter, Long commentId)
            throws Exception {
        startTogether.await(10, TimeUnit.SECONDS);
        try {
            reportService.report(reporter.getMemberId(), creator.getCreatorId(), postId, commentId,
                    CommentReportReason.ABUSE, null);
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private Long commentOnPublicPost() {
        Member owner = member(MemberRole.USER);
        author = member(MemberRole.USER);
        creator = creatorRepository.saveAndFlush(new Creator(owner.getMemberId(), "creator-" + suffix()));
        followService.follow(author.getMemberId(), creator.getCreatorId());
        postId = postService.create(owner.getMemberId(),
                new CreatorPostFields("본문", PostVisibility.PUBLIC, List.of())).postId();
        return commentService.create(author.getMemberId(), creator.getCreatorId(), postId, "신고될 댓글").commentId();
    }

    private Member member(MemberRole role) {
        String suffix = suffix();
        Member member = new Member("report-" + suffix, null, null, role);
        member.setInitialNickname(MemberNickname.of("report" + suffix));
        member = memberRepository.saveAndFlush(member);
        members.add(member);
        return member;
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
