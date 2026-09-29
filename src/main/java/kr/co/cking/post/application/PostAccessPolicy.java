package kr.co.cking.post.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.follow.application.CreatorFollowQueryService;
import kr.co.cking.post.domain.CreatorPost;
import kr.co.cking.post.domain.PostErrorCode;
import kr.co.cking.post.domain.PostVisibility;
import kr.co.cking.post.repository.CreatorPostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 게시글과 댓글이 함께 쓰는 조회·참여 권한 판단.
 *
 * <p>팔로워 공개(FOLLOWERS) 게시글은 팔로워와 작성한 Creator 본인만 볼 수 있다. 댓글 작성·수정은 게시글 공개 범위와
 * 무관하게 팔로워와 작성한 Creator 본인만 할 수 있다.
 */
@Component
@RequiredArgsConstructor
class PostAccessPolicy {

    private final CreatorRepository creatorRepository;
    private final CreatorPostRepository postRepository;
    private final CreatorFollowQueryService followQueryService;

    Creator requireCreator(Long creatorId) {
        return creatorRepository.findById(creatorId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    /** @param memberId 비로그인이면 null */
    boolean isFollowerOrOwner(Creator creator, Long memberId) {
        if (memberId == null) {
            return false;
        }
        return creator.getMemberId().equals(memberId)
                || followQueryService.isFollowing(memberId, creator.getCreatorId());
    }

    boolean isLocked(CreatorPost post, boolean followerOrOwner) {
        return post.getVisibility() == PostVisibility.FOLLOWERS && !followerOrOwner;
    }

    /**
     * 조회자가 볼 수 있는 게시글을 찾는다. 게시글이 없거나 다른 Creator의 게시글이면 404, 볼 수 없는 팔로워 공개
     * 게시글이면 {@code POST_FOLLOWERS_ONLY}(403)다.
     *
     * @param viewerMemberId 비로그인이면 null
     */
    CreatorPost requireViewablePost(Creator creator, Long postId, Long viewerMemberId) {
        return requireViewable(creator, postRepository.findById(postId), viewerMemberId);
    }

    /**
     * 댓글 작성·수정용. {@link #requireViewablePost}와 같지만 게시글 행을 공유 잠금으로 읽어, 동시에 진행 중인 게시글
     * 삭제가 끝날 때까지 기다린다. 삭제가 Commit되면 게시글이 없으므로 FK 오류(500) 대신 404가 된다.
     */
    CreatorPost requireViewablePostForCommentWrite(Creator creator, Long postId, Long memberId) {
        return requireViewable(creator, postRepository.findByIdForShare(postId), memberId);
    }

    /**
     * 댓글 삭제용. 공개 범위와 무관하게 해당 Creator의 게시글을 공유 잠금으로 찾는다. 없거나 다른 Creator의
     * 게시글이면 404다.
     */
    CreatorPost requirePostOfForCommentWrite(Creator creator, Long postId) {
        return requireOwnedBy(creator, postRepository.findByIdForShare(postId));
    }

    private CreatorPost requireViewable(Creator creator, Optional<CreatorPost> found, Long viewerMemberId) {
        CreatorPost post = requireOwnedBy(creator, found);
        if (isLocked(post, isFollowerOrOwner(creator, viewerMemberId))) {
            throw new BusinessException(PostErrorCode.POST_FOLLOWERS_ONLY);
        }
        return post;
    }

    private CreatorPost requireOwnedBy(Creator creator, Optional<CreatorPost> found) {
        return found.filter(post -> post.isWrittenBy(creator.getCreatorId()))
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }
}
