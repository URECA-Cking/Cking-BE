package kr.co.cking.post.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.post.domain.CreatorPost;
import kr.co.cking.post.domain.CreatorPostComment;
import kr.co.cking.post.repository.CreatorPostCommentRepository;
import kr.co.cking.post.repository.CreatorPostRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AdminCommentDeleteServiceTest {

    private static final Long ADMIN_ID = 1L;
    private static final Long COMMENT_ID = 500L;
    private static final Long POST_ID = 30L;

    private final MemberQueryService memberQueryService = mock(MemberQueryService.class);
    private final CreatorPostRepository postRepository = mock(CreatorPostRepository.class);
    private final CreatorPostCommentRepository commentRepository = mock(CreatorPostCommentRepository.class);
    private final AdminCommentDeleteService service =
            new AdminCommentDeleteService(memberQueryService, postRepository, commentRepository);

    @Test
    void 관리자는_신고_여부와_상관없이_게시글_공유_잠금_다음_댓글_쓰기_잠금을_잡고_삭제한다() {
        CreatorPostComment comment = mock(CreatorPostComment.class);
        given(commentRepository.findPostIdByCommentId(COMMENT_ID)).willReturn(Optional.of(POST_ID));
        given(postRepository.findByIdForShare(POST_ID)).willReturn(Optional.of(mock(CreatorPost.class)));
        given(commentRepository.findByIdForUpdate(COMMENT_ID)).willReturn(Optional.of(comment));

        service.delete(ADMIN_ID, COMMENT_ID);

        InOrder order = inOrder(memberQueryService, commentRepository, postRepository);
        order.verify(memberQueryService).validateAdmin(ADMIN_ID);
        order.verify(commentRepository).findPostIdByCommentId(COMMENT_ID);
        order.verify(postRepository).findByIdForShare(POST_ID);
        order.verify(commentRepository).findByIdForUpdate(COMMENT_ID);
        order.verify(commentRepository).delete(comment);
    }

    @Test
    void 관리자가_아니면_댓글을_읽지도_삭제하지도_않는다() {
        willThrow(new BusinessException(CommonErrorCode.FORBIDDEN)).given(memberQueryService).validateAdmin(7L);

        assertThatThrownBy(() -> service.delete(7L, COMMENT_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.FORBIDDEN));
        verifyNoInteractions(commentRepository, postRepository);
    }

    @Test
    void 없는_댓글은_404이고_삭제하지_않는다() {
        given(commentRepository.findPostIdByCommentId(COMMENT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(ADMIN_ID, COMMENT_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
        verify(commentRepository, never()).delete(any(CreatorPostComment.class));
    }

    @Test
    void 잠금_사이에_댓글이_사라졌으면_404다() {
        given(commentRepository.findPostIdByCommentId(COMMENT_ID)).willReturn(Optional.of(POST_ID));
        given(postRepository.findByIdForShare(POST_ID)).willReturn(Optional.of(mock(CreatorPost.class)));
        given(commentRepository.findByIdForUpdate(COMMENT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(ADMIN_ID, COMMENT_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
        verify(commentRepository, never()).delete(any(CreatorPostComment.class));
    }
}
