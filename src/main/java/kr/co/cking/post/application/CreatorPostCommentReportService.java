package kr.co.cking.post.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.post.application.dto.CommentReportView;
import kr.co.cking.post.domain.CommentReportReason;
import kr.co.cking.post.domain.CreatorPostComment;
import kr.co.cking.post.domain.CreatorPostCommentReport;
import kr.co.cking.post.domain.PostErrorCode;
import kr.co.cking.post.repository.CreatorPostCommentReportRepository;
import kr.co.cking.post.repository.CreatorPostCommentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 게시글 댓글 신고를 접수한다(이슈 #485).
 *
 * <p>신고는 댓글의 노출 상태나 필터 판정을 바꾸지 않는다. 신고할 수 있는 사람은 게시글을 볼 수 있는 로그인 사용자이며
 * (전체 공개 게시글은 팔로우하지 않아도 된다), 본인 댓글은 신고할 수 없다. 같은 사용자가 같은 댓글을 다시 신고하면 새로
 * 저장하지 않고 기존 신고를 그대로 돌려준다(멱등).
 *
 * <p>가장 먼저 신고자(회원) 행을 쓰기 잠금으로 읽는다. 반복 신고 한도는 "기존 신고 수를 읽고 새 신고를 저장"하는 두 단계라
 * 댓글별 잠금만으로는 같은 사용자가 서로 다른 댓글을 동시에 신고하는 경우를 막지 못한다(각 요청이 같은 기존 수를 읽는다).
 * 신고자 단위로 직렬화하면 확인과 저장이 한 번에 하나씩 일어나 한도가 지켜진다. 같은 회원의 명령을 직렬화하는
 * 관심 분야 저장과 같은 방식이다. 이어서 댓글 작성·수정·삭제와 같은 순서로 게시글 공유 잠금 다음에 댓글 행을 쓰기
 * 잠금으로 읽는다. 같은 댓글의 중복 신고가 직렬화되고, 먼저 삭제된 댓글은 FK 오류(500) 대신 404가 된다.
 *
 * <p>잠금 순서는 신고자 → 게시글 → 댓글이다. 다른 흐름은 신고자 행을 잠그는 동안 게시글·댓글 잠금을 기다리지 않으므로
 * 교착이 생기지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CreatorPostCommentReportService {

    /** 같은 신고자가 {@link #REPORT_LIMIT_WINDOW} 안에 접수할 수 있는 신고 수(새 신고만 센다). */
    static final int REPORT_LIMIT = 20;
    static final Duration REPORT_LIMIT_WINDOW = Duration.ofHours(1);

    private final PostAccessPolicy accessPolicy;
    private final CreatorPostCommentRepository commentRepository;
    private final CreatorPostCommentReportRepository reportRepository;
    private final MemberRepository memberRepository;
    private final Clock clock;

    public CommentReportView report(
            Long memberId, Long creatorId, Long postId, Long commentId,
            CommentReportReason reason, String detail) {
        String normalizedDetail = validate(reason, detail);
        memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        Creator creator = accessPolicy.requireCreator(creatorId);
        accessPolicy.requireViewablePostForCommentWrite(creator, postId, memberId);
        CreatorPostComment comment = commentRepository.findByIdForUpdate(commentId)
                .filter(found -> found.belongsTo(postId))
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (comment.isWrittenBy(memberId)) {
            throw new BusinessException(PostErrorCode.COMMENT_REPORT_OWN_COMMENT);
        }

        Optional<CreatorPostCommentReport> existing =
                reportRepository.findByCommentIdAndReporterMemberId(commentId, memberId);
        if (existing.isPresent()) {
            return CommentReportView.from(existing.get());
        }

        Instant now = clock.instant();
        if (reportRepository.countByReporterMemberIdAndCreatedAtGreaterThanEqual(
                memberId, now.minus(REPORT_LIMIT_WINDOW)) >= REPORT_LIMIT) {
            throw new BusinessException(PostErrorCode.COMMENT_REPORT_LIMIT_EXCEEDED);
        }
        return CommentReportView.from(reportRepository.save(
                new CreatorPostCommentReport(commentId, memberId, reason, normalizedDetail, now)));
    }

    /** 설명은 기타(OTHER)에만 필요하고 1~200자다. 그 밖의 사유에 설명이 오면 잘못된 요청이다. */
    private String validate(CommentReportReason reason, String detail) {
        if (reason == null) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        boolean blank = detail == null || detail.isBlank();
        if (reason == CommentReportReason.OTHER) {
            if (blank || detail.strip().length() > CreatorPostCommentReport.MAX_DETAIL_LENGTH) {
                throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
            }
            return detail.strip();
        }
        if (!blank) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        return null;
    }
}
