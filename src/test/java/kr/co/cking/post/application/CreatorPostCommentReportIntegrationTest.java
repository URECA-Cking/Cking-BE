package kr.co.cking.post.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.follow.application.CreatorFollowService;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.post.application.dto.AdminCommentReportView;
import kr.co.cking.post.application.dto.CommentReportView;
import kr.co.cking.post.application.dto.CreatorPostFields;
import kr.co.cking.post.domain.CommentReportReason;
import kr.co.cking.post.domain.CreatorPostCommentReport;
import kr.co.cking.post.domain.PostErrorCode;
import kr.co.cking.post.domain.PostVisibility;
import kr.co.cking.post.repository.CreatorPostCommentReportRepository;
import kr.co.cking.post.repository.CreatorPostCommentRepository;
import kr.co.cking.post.repository.CreatorPostRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
                admin.getMemberId(), PageRequest.of(0, 100)).getContent().stream()
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

        assertThatThrownBy(() -> adminQueryService.findReportedComments(user.getMemberId(), PageRequest.of(0, 20)))
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
        Member member = memberRepository.saveAndFlush(new Member("report-" + suffix(), null, null, role));
        members.add(member);
        return member;
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
