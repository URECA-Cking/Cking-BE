package kr.co.cking.post.application;

import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.post.application.dto.AdminCommentDisplay;
import kr.co.cking.post.application.dto.AdminFilteredCommentView;
import kr.co.cking.post.domain.CommentFilterAction;
import kr.co.cking.post.domain.CreatorPostComment;
import kr.co.cking.post.repository.CreatorPostCommentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 관리자가 필터가 차단한 댓글을 신고 여부와 상관없이 최근 판정 순으로 조회한다(이슈 #809). 이미 차단된 댓글은 새 신고를
 * 받지 않아 신고 목록만으로는 찾을 수 없다.
 */
@Service
@RequiredArgsConstructor
public class AdminFilteredCommentQueryService {

    private final MemberQueryService memberQueryService;
    private final AdminCommentDisplayReader displayReader;
    private final CreatorPostCommentRepository commentRepository;

    @Transactional(readOnly = true)
    public Page<AdminFilteredCommentView> findFilteredComments(Long adminId, Pageable pageable) {
        memberQueryService.validateAdmin(adminId);

        Page<CreatorPostComment> comments =
                commentRepository.findByFilterActionLatestFirst(CommentFilterAction.BLOCK, pageable);
        Map<Long, AdminCommentDisplay> displays = displayReader.readByCommentId(comments.getContent());

        return comments.map(comment -> {
            AdminCommentDisplay display = displays.get(comment.getCommentId());
            return new AdminFilteredCommentView(
                    comment.getCommentId(),
                    comment.getPostId(),
                    display.postPreview(),
                    display.creatorId(),
                    display.creatorName(),
                    comment.getMemberId(),
                    display.authorName(),
                    comment.getContent(),
                    comment.blockReasons(),
                    comment.getFilterStatus(),
                    comment.getFilteredAt());
        });
    }
}
