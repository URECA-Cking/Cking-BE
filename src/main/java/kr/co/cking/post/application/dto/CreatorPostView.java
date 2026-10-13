package kr.co.cking.post.application.dto;

import kr.co.cking.post.domain.PostVisibility;

import java.time.Instant;
import java.util.List;

/**
 * 조회자 기준으로 가공한 게시글.
 *
 * @param locked     팔로워 공개 게시글을 볼 권한이 없으면 true. 이때 {@code content}는 null, {@code images}는 비어 있다.
 * @param imageCount 잠금 여부와 무관한 이미지 수
 * @param likeCount  좋아요 수. 잠긴 게시글도 포함한다
 * @param liked      조회자가 좋아요했는지. 비로그인이면 false
 * @param commentCount 댓글 수. 필터·관리자 숨김 댓글도 센다
 */
public record CreatorPostView(
        Long postId,
        Long creatorId,
        PostVisibility visibility,
        boolean locked,
        String content,
        int imageCount,
        List<Image> images,
        long likeCount,
        boolean liked,
        long commentCount,
        Instant createdAt,
        Instant updatedAt
) {

    /** @param url 유효 시간이 제한된 이미지 조회 주소 */
    public record Image(String imageKey, String url) {
    }
}
