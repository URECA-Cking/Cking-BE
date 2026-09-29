package kr.co.cking.post.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.post.application.dto.CreatorPostFields;
import kr.co.cking.post.application.dto.CreatorPostView;
import kr.co.cking.post.domain.CreatorPost;
import kr.co.cking.post.domain.CreatorPostImage;
import kr.co.cking.post.domain.PostErrorCode;
import kr.co.cking.post.repository.CreatorPostCommentRepository;
import kr.co.cking.post.repository.CreatorPostImageRepository;
import kr.co.cking.post.repository.CreatorPostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Creator 본인의 게시글 작성·수정·삭제를 처리한다(이슈 #318).
 *
 * <p>이미지는 업로드 기록을 조건부 UPDATE로 연결하고, 변경된 행 수가 요청 key 수와 다르면 거부한다. 게시글에서
 * 빠진 이미지는 같은 Transaction에서 DELETE_PENDING으로 바꾸고 Commit 후 저장소에서 지운다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CreatorPostService {

    private final PostAuthorLookup authorLookup;
    private final CreatorPostRepository postRepository;
    private final CreatorPostImageRepository imageRepository;
    private final CreatorPostCommentRepository commentRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final CreatorPostViewAssembler viewAssembler;
    private final Clock clock;

    /**
     * @return 작성자 기준 게시글. Commit 뒤에 실패할 단계를 남기지 않도록 응답을 이 Transaction 안에서 만든다.
     */
    public CreatorPostView create(Long memberId, CreatorPostFields fields) {
        Creator creator = authorLookup.requireCreator(memberId);
        List<String> imageKeys = validate(fields);
        Instant now = clock.instant();

        CreatorPost post = postRepository.save(
                new CreatorPost(creator.getCreatorId(), content(fields), fields.visibility(), now));
        link(imageKeys, creator.getCreatorId(), post.getPostId(), now);
        applyDisplayOrder(imageKeys, post.getPostId());
        return viewAssembler.assemble(post, imageKeys, false);
    }

    /**
     * @return 작성자 기준 게시글. Commit 뒤에 실패할 단계를 남기지 않도록 응답을 이 Transaction 안에서 만든다.
     */
    public CreatorPostView update(Long memberId, Long postId, CreatorPostFields fields) {
        Creator creator = authorLookup.requireCreator(memberId);
        CreatorPost post = requireOwnedPostForUpdate(postId, creator.getCreatorId());
        List<String> imageKeys = validate(fields);
        Instant now = clock.instant();

        Set<String> currentKeys = currentImageKeys(postId);
        List<String> removedKeys = currentKeys.stream().filter(key -> !imageKeys.contains(key)).toList();
        List<String> addedKeys = imageKeys.stream().filter(key -> !currentKeys.contains(key)).toList();

        post.update(content(fields), fields.visibility(), now);
        release(postId, removedKeys, now);
        link(addedKeys, creator.getCreatorId(), postId, now);
        applyDisplayOrder(imageKeys, postId);
        return viewAssembler.assemble(post, imageKeys, false);
    }

    public void delete(Long memberId, Long postId) {
        Creator creator = authorLookup.requireCreator(memberId);
        CreatorPost post = requireOwnedPostForUpdate(postId, creator.getCreatorId());
        release(postId, List.copyOf(currentImageKeys(postId)), clock.instant());
        commentRepository.deleteByPostId(postId);
        postRepository.delete(post);
    }

    /** 존재하지 않으면 404, 다른 Creator의 게시글이면 403 — 존재 여부와 소유권을 구분한다. */
    private CreatorPost requireOwnedPostForUpdate(Long postId, Long creatorId) {
        CreatorPost post = postRepository.findByIdForUpdate(postId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (!post.isWrittenBy(creatorId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return post;
    }

    /** @return 중복 없는 이미지 key 목록(요청 순서 유지) */
    private List<String> validate(CreatorPostFields fields) {
        List<String> imageKeys = fields.imageKeys() == null ? List.of() : fields.imageKeys();
        String content = content(fields);
        boolean invalid = fields.visibility() == null
                || content.length() > CreatorPost.MAX_CONTENT_LENGTH
                || imageKeys.size() > CreatorPost.MAX_IMAGE_COUNT
                || imageKeys.stream().anyMatch(key -> key == null || key.isBlank())
                || new HashSet<>(imageKeys).size() != imageKeys.size()
                || (content.isBlank() && imageKeys.isEmpty());
        if (invalid) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        return imageKeys;
    }

    private String content(CreatorPostFields fields) {
        return fields.content() == null ? "" : fields.content();
    }

    private Set<String> currentImageKeys(Long postId) {
        Set<String> keys = new LinkedHashSet<>();
        for (CreatorPostImage image : imageRepository.findByPostIdOrderByDisplayOrderAsc(postId)) {
            keys.add(image.getObjectKey());
        }
        return keys;
    }

    private void link(List<String> imageKeys, Long creatorId, Long postId, Instant now) {
        if (imageKeys.isEmpty()) {
            return;
        }
        int linked = imageRepository.linkToPost(imageKeys, creatorId, postId, now);
        if (linked != imageKeys.size()) {
            throw new BusinessException(PostErrorCode.POST_IMAGE_UNAVAILABLE);
        }
    }

    private void applyDisplayOrder(List<String> imageKeys, Long postId) {
        for (int order = 0; order < imageKeys.size(); order++) {
            imageRepository.updateDisplayOrder(imageKeys.get(order), postId, order);
        }
    }

    private void release(Long postId, List<String> imageKeys, Instant now) {
        if (imageKeys.isEmpty()) {
            return;
        }
        imageRepository.releaseFromPost(postId, imageKeys, now);
        eventPublisher.publishEvent(new PostImagesReleasedEvent(imageKeys));
    }
}
