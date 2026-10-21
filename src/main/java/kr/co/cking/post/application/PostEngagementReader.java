package kr.co.cking.post.application;

import kr.co.cking.post.application.dto.PostEngagement;
import kr.co.cking.post.repository.CreatorPostCommentRepository;
import kr.co.cking.post.repository.CreatorPostLikeRepository;
import kr.co.cking.post.repository.PostCount;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 게시글의 좋아요 수·내 좋아요 여부·댓글 수를 읽는다(이슈 #554). 목록은 게시글마다 조회하지 않고 한 페이지를
 * 일괄 집계한다(쿼리 3개 이하).
 */
@Component
@RequiredArgsConstructor
class PostEngagementReader {

    private final CreatorPostLikeRepository likeRepository;
    private final CreatorPostCommentRepository commentRepository;

    /**
     * @param viewerMemberId 비로그인이면 null
     * @param viewerIsOwner 조회자가 게시글 소유 Creator 본인이면 true. 아니면 댓글 수에서 가려진 댓글(조회자 본인이 쓴 것 제외)을 뺀다.
     * @return 게시글 ID별 반응. {@code postIds}의 모든 ID가 키로 들어 있다.
     */
    Map<Long, PostEngagement> read(Collection<Long> postIds, Long viewerMemberId, boolean viewerIsOwner) {
        if (postIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> likeCounts = toMap(likeRepository.countByPostIds(postIds));
        Map<Long, Long> commentCounts = toMap(viewerIsOwner
                ? commentRepository.countByPostIds(postIds)
                : commentRepository.countVisibleByPostIds(postIds, viewerMemberId));
        Set<Long> liked = viewerMemberId == null
                ? Set.of()
                : new HashSet<>(likeRepository.findLikedPostIds(viewerMemberId, postIds));

        Map<Long, PostEngagement> result = new HashMap<>();
        for (Long postId : postIds) {
            result.put(postId, new PostEngagement(
                    likeCounts.getOrDefault(postId, 0L),
                    liked.contains(postId),
                    commentCounts.getOrDefault(postId, 0L)));
        }
        return result;
    }

    PostEngagement read(Long postId, Long viewerMemberId, boolean viewerIsOwner) {
        return read(List.of(postId), viewerMemberId, viewerIsOwner).get(postId);
    }

    private Map<Long, Long> toMap(List<PostCount> counts) {
        Map<Long, Long> map = new HashMap<>();
        counts.forEach(count -> map.put(count.getPostId(), count.getTotal()));
        return map;
    }
}
