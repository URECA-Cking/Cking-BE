package kr.co.cking.post.application.dto;

import kr.co.cking.post.domain.PostVisibility;

import java.time.Instant;
import java.util.List;

/**
 * 조회자 기준으로 가공한 게시글.
 *
 * @param locked     팔로워 공개 게시글을 볼 권한이 없으면 true. 이때 {@code content}는 null, {@code images}는 비어 있다.
 * @param imageCount 잠금 여부와 무관한 이미지 수
 */
public record CreatorPostView(
        Long postId,
        Long creatorId,
        PostVisibility visibility,
        boolean locked,
        String content,
        int imageCount,
        List<Image> images,
        Instant createdAt,
        Instant updatedAt
) {

    /** @param url 유효 시간이 제한된 이미지 조회 주소 */
    public record Image(String imageKey, String url) {
    }
}
