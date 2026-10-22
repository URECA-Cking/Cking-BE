package kr.co.cking.post.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.post.application.dto.AdminCommentReportActionView;
import kr.co.cking.post.domain.CommentReportStatus;
import kr.co.cking.post.domain.CreatorPost;
import kr.co.cking.post.domain.CreatorPostComment;
import kr.co.cking.post.domain.PostErrorCode;
import kr.co.cking.post.repository.CreatorPostCommentReportRepository;
import kr.co.cking.post.repository.CreatorPostCommentRepository;
import kr.co.cking.post.repository.CreatorPostRepository;
import kr.co.cking.sanction.application.SanctionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * 관리자가 신고된 댓글을 숨기거나 신고를 반려한다(이슈 #499). 조치는 댓글 단위로, 그 댓글의 접수(PENDING) 신고를
 * 한 번에 처리한다.
 *
 * <ul>
 *   <li>숨김: 댓글을 관리자 숨김으로 표시하고 접수 신고를 RESOLVED로 바꾼다. 숨김은 되돌리지 않는다.</li>
 *   <li>반려: 댓글은 그대로 두고 접수 신고를 REJECTED로 바꾼다.</li>
 * </ul>
 *
 * <p>같은 조치를 다시 요청하면 아무것도 바꾸지 않고 성공한다(멱등). 이미 숨긴 댓글을 반려하거나, 접수 신고가 모두
 * 반려된 댓글을 숨기려 하면 서로 모순되는 조치라 {@code COMMENT_REPORT_ALREADY_HANDLED}(409)다. 신고가 하나도 없는
 * 댓글은 404다.
 *
 * <p>잠금 순서는 신고 접수·댓글 작성·수정·삭제와 같은 게시글 공유 잠금 → 댓글 쓰기 잠금이다(관리자는 신고자 행을
 * 잠그지 않는다). 댓글 행을 잠근 채 숨김 여부와 접수 신고를 읽고 바꾸므로, 같은 댓글에 들어오는 신고 접수와 직렬화돼
 * 숨김 직후 새 신고가 접수 상태로 남는 일이 없다.
 *
 * <p>숨김은 작성자에게 경고로 기록되고, 그 스페이스에서의 경고 횟수에 따라 <b>그 스페이스의</b> 댓글 작성 제한이 생긴다(이슈
 * #569, #643). 작성자가 관리자이거나 그 스페이스의 주인이면 경고도 제한도 만들지 않는다. 경고를 센 뒤 저장하는 두
 * 단계라 같은 작성자의 처리를 직렬화해야 하므로, 숨김은 작성자 행을 가장 먼저 쓰기 잠금으로 읽는다. 잠금 순서는 작성자
 * → 게시글 → 댓글로 신고 접수({@link CreatorPostCommentReportService})와 같아 교착이 생기지 않는다. 이미 숨긴 댓글을
 * 다시 숨기는 요청은 경고를 새로 쌓지 않는다.
 *
 * <p>숨김은 {@code READ_COMMITTED}로 실행한다. 관리자 검증과 작성자 조회(일반 조회)가 작성자 행 잠금보다 먼저 있어서
 * REPEATABLE READ에서는 그 시점에 스냅샷이 고정되고, 잠금에서 기다렸다가 풀려도 경고 집계가 앞선 요청이 커밋한 경고를
 * 못 본다. 같은 작성자의 서로 다른 댓글을 동시에 숨기면 두 제한이 모두 1일로 저장되는 식으로 과소 집계된다.
 * READ_COMMITTED에서는 문장마다 최신 커밋을 읽으므로 잠금 이후의 집계가 앞선 경고를 센다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AdminCommentReportCommandService {

    private final MemberQueryService memberQueryService;
    private final CreatorPostRepository postRepository;
    private final CreatorPostCommentRepository commentRepository;
    private final CreatorPostCommentReportRepository reportRepository;
    private final SanctionService sanctionService;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminCommentReportActionView hide(Long adminId, Long commentId) {
        memberQueryService.validateAdmin(adminId);
        Long authorId = commentRepository.findMemberIdByCommentId(commentId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        sanctionService.lockSubject(authorId);
        ReportedComment reported = lockReportedComment(commentId);
        CreatorPostComment comment = reported.comment();
        if (comment.isHiddenByAdmin()) {
            return new AdminCommentReportActionView(commentId, CommentReportStatus.RESOLVED, 0);
        }
        Instant now = clock.instant();
        int handled = reportRepository.markHandled(commentId, CommentReportStatus.RESOLVED, adminId, now);
        if (handled == 0) {
            // 접수 신고가 없는데 숨기지도 않은 댓글은 이미 반려로 처리됐다.
            throw new BusinessException(PostErrorCode.COMMENT_REPORT_ALREADY_HANDLED);
        }
        comment.hideByAdmin(adminId, now);
        // 경고와 제한은 숨긴 댓글이 달린 스페이스에서만 만들어진다. 작성자가 관리자이거나 그 스페이스의 주인이면 만들지 않는다.
        sanctionService.recordCommentHide(comment.getMemberId(), commentId, reported.creatorId(), adminId, now);
        return new AdminCommentReportActionView(commentId, CommentReportStatus.RESOLVED, handled);
    }

    public AdminCommentReportActionView reject(Long adminId, Long commentId) {
        memberQueryService.validateAdmin(adminId);
        CreatorPostComment comment = lockReportedComment(commentId).comment();
        if (comment.isHiddenByAdmin()) {
            throw new BusinessException(PostErrorCode.COMMENT_REPORT_ALREADY_HANDLED);
        }
        int handled = reportRepository.markHandled(commentId, CommentReportStatus.REJECTED, adminId, clock.instant());
        // handled가 0이면 접수 신고가 모두 이미 반려돼 있다. 같은 조치의 재요청이라 그대로 성공시킨다.
        return new AdminCommentReportActionView(commentId, CommentReportStatus.REJECTED, handled);
    }

    /**
     * 게시글 공유 잠금 다음에 댓글을 쓰기 잠금으로 읽는다. 댓글이 없거나 신고가 하나도 없으면 404다.
     * 게시글 ID는 엔티티를 영속성 컨텍스트에 올리지 않는 값 조회로 읽어, 잠금을 잡은 뒤 읽는 댓글이 최신 상태이게 한다.
     */
    private ReportedComment lockReportedComment(Long commentId) {
        Long postId = commentRepository.findPostIdByCommentId(commentId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        CreatorPost post = postRepository.findByIdForShare(postId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        CreatorPostComment comment = commentRepository.findByIdForUpdate(commentId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (reportRepository.countByCommentId(commentId) == 0) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        return new ReportedComment(comment, post.getCreatorId());
    }

    /** 잠근 신고된 댓글과 그 댓글이 달린 게시글의 스페이스 주인(Creator ID). */
    private record ReportedComment(CreatorPostComment comment, Long creatorId) {
    }
}
