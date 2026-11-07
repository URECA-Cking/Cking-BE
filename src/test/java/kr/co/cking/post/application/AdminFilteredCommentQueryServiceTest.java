package kr.co.cking.post.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.application.CreatorPublicProfileQueryService;
import kr.co.cking.creator.application.dto.CreatorPublicProfile;
import kr.co.cking.member.application.MemberInfo;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.post.application.dto.AdminFilteredCommentView;
import kr.co.cking.post.domain.CommentFilterAction;
import kr.co.cking.post.domain.CommentFilterStatus;
import kr.co.cking.post.domain.CreatorPost;
import kr.co.cking.post.domain.CreatorPostComment;
import kr.co.cking.post.domain.PostVisibility;
import kr.co.cking.post.repository.CreatorPostCommentRepository;
import kr.co.cking.post.repository.CreatorPostRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static kr.co.cking.post.PostFixtures.NOW;
import static kr.co.cking.post.PostFixtures.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AdminFilteredCommentQueryServiceTest {

    private static final PageRequest PAGE = PageRequest.of(0, 20);

    private final MemberQueryService memberQueryService = mock(MemberQueryService.class);
    private final CreatorPublicProfileQueryService creatorProfileService = mock(CreatorPublicProfileQueryService.class);
    private final CreatorPostCommentRepository commentRepository = mock(CreatorPostCommentRepository.class);
    private final CreatorPostRepository postRepository = mock(CreatorPostRepository.class);
    private final AdminFilteredCommentQueryService service = new AdminFilteredCommentQueryService(
            memberQueryService,
            new AdminCommentDisplayReader(memberQueryService, creatorProfileService, postRepository),
            commentRepository);

    @Test
    void 차단_댓글을_사유와_표시_이름과_함께_돌려준다() {
        givenComments(blocked(500L, 100L, 20L, "차단된 댓글", "spam:link"));
        given(postRepository.findAllById(any())).willReturn(List.of(post(100L, 1L, PostVisibility.PUBLIC)));
        given(creatorProfileService.findByCreatorIds(Set.of(1L)))
                .willReturn(Map.of(1L, new CreatorPublicProfile(1L, "활동명", null)));
        given(creatorProfileService.findByMemberIds(Set.of(20L))).willReturn(Map.of());
        given(memberQueryService.findMemberInfosByIds(Set.of(20L)))
                .willReturn(Map.of(20L, new MemberInfo(20L, "닉네임", null, null)));

        AdminFilteredCommentView view = service.findFilteredComments(1L, PAGE).getContent().get(0);

        verify(memberQueryService).validateAdmin(1L);
        assertThat(view.commentId()).isEqualTo(500L);
        assertThat(view.postId()).isEqualTo(100L);
        assertThat(view.postPreview()).isEqualTo("본문 100");
        assertThat(view.creatorId()).isEqualTo(1L);
        assertThat(view.creatorName()).isEqualTo("활동명");
        assertThat(view.authorMemberId()).isEqualTo(20L);
        assertThat(view.authorName()).isEqualTo("닉네임");
        assertThat(view.content()).isEqualTo("차단된 댓글");
        assertThat(view.filterReasons()).containsExactly("spam:link");
        assertThat(view.filterStatus()).isEqualTo(CommentFilterStatus.DONE);
        assertThat(view.filteredAt()).isEqualTo(NOW);
    }

    @Test
    void Creator_계정의_댓글은_닉네임_대신_활동명으로_보여준다() {
        givenComments(blocked(500L, 100L, 20L, "차단된 댓글", "model"));
        given(postRepository.findAllById(any())).willReturn(List.of(post(100L, 1L, PostVisibility.PUBLIC)));
        given(creatorProfileService.findByMemberIds(Set.of(20L)))
                .willReturn(Map.of(20L, new CreatorPublicProfile(2L, "작성자 활동명", null)));
        given(memberQueryService.findMemberInfosByIds(Set.of(20L)))
                .willReturn(Map.of(20L, new MemberInfo(20L, "닉네임", null, null)));

        assertThat(service.findFilteredComments(1L, PAGE).getContent().get(0).authorName()).isEqualTo("작성자 활동명");
    }

    @Test
    void 회원이나_Creator를_찾지_못하면_이름은_null이다() {
        givenComments(blocked(500L, 100L, 20L, "차단된 댓글", "model"));
        given(postRepository.findAllById(any())).willReturn(List.of(post(100L, 1L, PostVisibility.PUBLIC)));

        AdminFilteredCommentView view = service.findFilteredComments(1L, PAGE).getContent().get(0);

        assertThat(view.authorName()).isNull();
        assertThat(view.creatorName()).isNull();
    }

    @Test
    void 게시글_미리보기는_앞_50자이고_이모지를_반으로_자르지_않는다() {
        String content = "가".repeat(49) + "😀" + "나머지";
        CreatorPost longPost = new CreatorPost(1L, content, PostVisibility.PUBLIC, NOW);
        ReflectionTestUtils.setField(longPost, "postId", 100L);
        givenComments(blocked(500L, 100L, 20L, "차단된 댓글", "model"));
        given(postRepository.findAllById(any())).willReturn(List.of(longPost));

        assertThat(service.findFilteredComments(1L, PAGE).getContent().get(0).postPreview())
                .isEqualTo("가".repeat(49) + "😀");
    }

    @Test
    void 재판정_전인_댓글은_판정_상태로_구별된다() {
        CreatorPostComment comment = blocked(500L, 100L, 20L, "차단된 댓글", "spam:link");
        comment.update("고친 본문", NOW.plusSeconds(10));
        givenComments(comment);
        given(postRepository.findAllById(any())).willReturn(List.of(post(100L, 1L, PostVisibility.PUBLIC)));

        AdminFilteredCommentView view = service.findFilteredComments(1L, PAGE).getContent().get(0);

        assertThat(view.filterStatus()).isEqualTo(CommentFilterStatus.PENDING);
        assertThat(view.content()).isEqualTo("고친 본문");
        assertThat(view.filterReasons()).containsExactly("spam:link");
    }

    @Test
    void 관리자가_아니면_조회하지_않고_거절한다() {
        willThrow(new BusinessException(CommonErrorCode.FORBIDDEN)).given(memberQueryService).validateAdmin(1L);

        assertThatThrownBy(() -> service.findFilteredComments(1L, PAGE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.FORBIDDEN);
        verify(commentRepository, never()).findByFilterActionLatestFirst(any(), any());
    }

    private void givenComments(CreatorPostComment... comments) {
        Page<CreatorPostComment> page = new PageImpl<>(List.of(comments), PAGE, comments.length);
        given(commentRepository.findByFilterActionLatestFirst(CommentFilterAction.BLOCK, PAGE)).willReturn(page);
    }

    private CreatorPostComment blocked(Long commentId, Long postId, Long memberId, String content, String reason) {
        CreatorPostComment comment = new CreatorPostComment(postId, memberId, content, NOW);
        ReflectionTestUtils.setField(comment, "commentId", commentId);
        comment.markFiltered(CommentFilterAction.BLOCK, List.of(reason), "rule-1", "model-1", NOW);
        return comment;
    }
}
