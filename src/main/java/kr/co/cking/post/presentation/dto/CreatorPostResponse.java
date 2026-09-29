package kr.co.cking.post.presentation.dto;

import kr.co.cking.post.application.dto.CreatorPostView;
import kr.co.cking.post.domain.PostVisibility;

import java.time.Instant;
import java.util.List;

public final class CreatorPostResponse {

    private CreatorPostResponse() {
    }

    /** 게시글. 잠금 상태면 {@code content}는 null, {@code images}는 빈 배열이다. */
    public record Detail(
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

        public static Detail from(CreatorPostView view) {
            return new Detail(
                    view.postId(),
                    view.creatorId(),
                    view.visibility(),
                    view.locked(),
                    view.content(),
                    view.imageCount(),
                    view.images().stream().map(image -> new Image(image.imageKey(), image.url())).toList(),
                    view.createdAt(),
                    view.updatedAt());
        }
    }

    /** @param url 유효 시간 10분의 이미지 조회 주소 */
    public record Image(String imageKey, String url) {
    }

    /** 업로드한 이미지 key. 게시글 작성·수정 요청의 {@code imageKeys}에 넣는다. */
    public record UploadedImage(String imageKey) {
    }
}
