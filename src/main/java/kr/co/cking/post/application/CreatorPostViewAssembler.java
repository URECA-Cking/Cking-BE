package kr.co.cking.post.application;

import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.post.application.dto.CreatorPostView;
import kr.co.cking.post.domain.CreatorPost;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 게시글과 이미지 key로 조회자 기준 {@link CreatorPostView}를 만든다. 작성·수정 응답과 공개 조회가 함께 쓴다.
 *
 * <p>이미지 조회 주소 발급은 최선 노력이다. 발급이 실패한 이미지는 {@code url}이 null이며, 게시글 저장이나 목록
 * 전체 응답을 실패시키지 않는다. 호출자는 {@code imageKey}로 다시 조회할 수 있다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CreatorPostViewAssembler {

    static final Duration IMAGE_URL_TTL = Duration.ofMinutes(10);

    private final ObjectStorage objectStorage;

    /**
     * @param imageKeys 표시 순서대로 정렬된 이미지 key
     * @param locked    true면 본문과 이미지 주소를 넣지 않는다
     */
    public CreatorPostView assemble(CreatorPost post, List<String> imageKeys, boolean locked) {
        List<CreatorPostView.Image> images = locked
                ? List.of()
                : imageKeys.stream().map(key -> new CreatorPostView.Image(key, imageUrl(key))).toList();
        return new CreatorPostView(
                post.getPostId(),
                post.getCreatorId(),
                post.getVisibility(),
                locked,
                locked ? null : post.getContent(),
                imageKeys.size(),
                images,
                post.getCreatedAt(),
                post.getUpdatedAt());
    }

    private String imageUrl(String objectKey) {
        try {
            return objectStorage.presignedGetUrl(objectKey, IMAGE_URL_TTL).toString();
        } catch (RuntimeException exception) {
            log.warn("게시글 이미지 조회 주소를 발급하지 못했습니다. objectKey={}", objectKey, exception);
            return null;
        }
    }
}
