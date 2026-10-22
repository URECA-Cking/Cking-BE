package kr.co.cking.post.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.post.domain.CreatorPostComment;
import kr.co.cking.post.repository.CreatorPostCommentRepository;
import kr.co.cking.post.repository.CreatorPostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자가 신고 접수 여부와 상관없이 댓글을 삭제한다(이슈 #584).
 *
 * <p>삭제는 크리에이터·작성자의 삭제와 같은 물리 삭제다. 그 댓글의 신고는 FK {@code ON DELETE CASCADE}로 함께 지워지고,
 * 게시글의 댓글 수는 집계 쿼리라 따로 맞출 값이 없다. 삭제는 작성자에게 경고로 세지 않고(경고는 신고 기반 숨김만),
 * 작성자에게 알림도 보내지 않으며, 삭제 이력도 남기지 않는다.
 *
 * <p>잠금 순서는 댓글 작성·수정·삭제, 신고 접수·조치와 같은 게시글 공유 잠금 → 댓글 쓰기 잠금이다. 작성자 행은 잠그지 않으므로
 * 작성자 → 게시글 → 댓글 순서로 잠그는 숨김 처리와 교착이 생기지 않는다. 게시글 ID는 엔티티를 영속성 컨텍스트에 올리지 않는
 * 값 조회로 읽어, 잠금을 잡은 뒤 읽는 댓글이 최신 상태이게 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AdminCommentDeleteService {

    private final MemberQueryService memberQueryService;
    private final CreatorPostRepository postRepository;
    private final CreatorPostCommentRepository commentRepository;

    /**
     * 댓글을 삭제한다.
     *
     * @throws BusinessException 관리자가 아니면 {@code FORBIDDEN}, 댓글이나 게시글이 없으면 {@code RESOURCE_NOT_FOUND}
     */
    public void delete(Long adminId, Long commentId) {
        memberQueryService.validateAdmin(adminId);
        Long postId = commentRepository.findPostIdByCommentId(commentId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        postRepository.findByIdForShare(postId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        CreatorPostComment comment = commentRepository.findByIdForUpdate(commentId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        commentRepository.delete(comment);
    }
}
