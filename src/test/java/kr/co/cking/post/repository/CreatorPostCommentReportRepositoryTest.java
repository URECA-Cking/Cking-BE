package kr.co.cking.post.repository;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.post.domain.CommentReportReason;
import kr.co.cking.post.domain.CreatorPost;
import kr.co.cking.post.domain.CreatorPostComment;
import kr.co.cking.post.domain.CreatorPostCommentReport;
import kr.co.cking.post.domain.PostVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/** 실제 MySQL에서 기타 신고 설명을 댓글별 최근 순으로 제한해 읽는지 확인한다(이슈 #811). */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class CreatorPostCommentReportRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00.123456Z");

    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private CreatorRepository creatorRepository;
    @Autowired
    private CreatorPostRepository postRepository;
    @Autowired
    private CreatorPostCommentRepository commentRepository;
    @Autowired
    private CreatorPostCommentReportRepository reportRepository;

    private Long postId;
    private Long authorId;

    @BeforeEach
    void setUp() {
        Member owner = member("신고 테스트 Creator");
        Creator creator = creatorRepository.saveAndFlush(new Creator(owner.getMemberId(), "신고 테스트 Creator"));
        authorId = member("신고 테스트 작성자").getMemberId();
        postId = postRepository.saveAndFlush(
                new CreatorPost(creator.getCreatorId(), "본문", PostVisibility.PUBLIC, NOW)).getPostId();
    }

    @Test
    void 댓글별로_최근_설명만_제한한_개수까지_읽는다() {
        Long crowded = comment();
        Long quiet = comment();
        report(crowded, CommentReportReason.OTHER, "가장 오래된 설명", NOW);
        report(crowded, CommentReportReason.OTHER, "두 번째 설명", NOW.plusSeconds(10));
        report(crowded, CommentReportReason.OTHER, "최근 설명", NOW.plusSeconds(20));
        report(quiet, CommentReportReason.OTHER, "다른 댓글 설명", NOW);

        List<CommentReportDetail> details = reportRepository.findDetails(List.of(crowded, quiet), 2);

        assertThat(details.stream().filter(row -> row.getCommentId().equals(crowded)).map(CommentReportDetail::getDetail))
                .containsExactly("최근 설명", "두 번째 설명");
        assertThat(details.stream().filter(row -> row.getCommentId().equals(quiet)).map(CommentReportDetail::getDetail))
                .containsExactly("다른 댓글 설명");
    }

    @Test
    void 같은_시각이면_나중에_접수한_신고가_먼저다() {
        Long commentId = comment();
        report(commentId, CommentReportReason.OTHER, "먼저 접수", NOW);
        report(commentId, CommentReportReason.OTHER, "나중 접수", NOW);

        assertThat(reportRepository.findDetails(List.of(commentId), 1))
                .extracting(CommentReportDetail::getDetail)
                .containsExactly("나중 접수");
    }

    @Test
    void 설명이_없는_신고는_읽지_않는다() {
        Long commentId = comment();
        report(commentId, CommentReportReason.ABUSE, null, NOW);

        assertThat(reportRepository.findDetails(List.of(commentId), 20)).isEmpty();
    }

    private Long comment() {
        return commentRepository.saveAndFlush(new CreatorPostComment(postId, authorId, "댓글", NOW)).getCommentId();
    }

    private void report(Long commentId, CommentReportReason reason, String detail, Instant at) {
        Long reporterId = member("신고자").getMemberId();
        reportRepository.saveAndFlush(new CreatorPostCommentReport(commentId, reporterId, reason, detail, at));
    }

    private Member member(String name) {
        return memberRepository.saveAndFlush(new Member(name, null, null, MemberRole.USER));
    }
}
