package kr.co.cking.post.application;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.post.application.dto.CreatorPostView;
import kr.co.cking.post.application.dto.PostEngagement;
import kr.co.cking.post.domain.CreatorPost;
import kr.co.cking.post.domain.CreatorPostImage;
import kr.co.cking.post.repository.CreatorPostImageRepository;
import kr.co.cking.post.repository.CreatorPostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 게시글 공개 조회(이슈 #318). 비로그인도 조회할 수 있다.
 *
 * <p>팔로워 공개(FOLLOWERS) 게시글은 팔로워와 작성한 Creator 본인에게만 본문·이미지를 제공한다. 목록에서는 잠금
 * 상태로 노출하고, 상세는 {@code POST_FOLLOWERS_ONLY}(403)다. 이미지는 권한을 확인한 뒤에만 유효 시간 10분의
 * 조회 주소를 발급한다. 좋아요 수·내 좋아요 여부·댓글 수(이슈 #554)는 잠금과 무관하게 한 페이지를 일괄 집계해
 * 함께 내려준다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CreatorPostQueryService {

    private final PostAccessPolicy accessPolicy;
    private final CreatorPostRepository postRepository;
    private final CreatorPostImageRepository imageRepository;
    private final CreatorPostViewAssembler viewAssembler;
    private final PostEngagementReader engagementReader;

    /** @param viewerMemberId 비로그인이면 null */
    public Page<CreatorPostView> findByCreator(Long creatorId, Long viewerMemberId, Pageable pageable) {
        Creator creator = accessPolicy.requireCreator(creatorId);
        boolean followerOrOwner = accessPolicy.isFollowerOrOwner(creator, viewerMemberId);

        Page<CreatorPost> posts = postRepository.findByCreatorIdLatestFirst(creatorId, pageable);
        Map<Long, List<String>> imageKeysByPost = posts.isEmpty()
                ? Map.of()
                : imageRepository.findByPostIdInOrderByPostIdAscDisplayOrderAsc(
                                posts.map(CreatorPost::getPostId).toList())
                        .stream()
                        .collect(Collectors.groupingBy(
                                CreatorPostImage::getPostId,
                                Collectors.mapping(CreatorPostImage::getObjectKey, Collectors.toList())));

        Map<Long, PostEngagement> engagements = engagementReader.read(
                posts.map(CreatorPost::getPostId).toList(), viewerMemberId);

        return posts.map(post -> viewAssembler.assemble(
                post,
                imageKeysByPost.getOrDefault(post.getPostId(), List.of()),
                accessPolicy.isLocked(post, followerOrOwner),
                engagements.get(post.getPostId())));
    }

    /** @param viewerMemberId 비로그인이면 null */
    public CreatorPostView findDetail(Long creatorId, Long postId, Long viewerMemberId) {
        Creator creator = accessPolicy.requireCreator(creatorId);
        CreatorPost post = accessPolicy.requireViewablePost(creator, postId, viewerMemberId);
        List<String> imageKeys = imageRepository.findByPostIdOrderByDisplayOrderAsc(postId).stream()
                .map(CreatorPostImage::getObjectKey)
                .toList();
        return viewAssembler.assemble(post, imageKeys, false, engagementReader.read(postId, viewerMemberId));
    }
}
