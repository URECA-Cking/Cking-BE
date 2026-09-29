package kr.co.cking.post.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import kr.co.cking.post.domain.CreatorPostComment;

/** 댓글 작성·수정 요청. */
public record CreatorPostCommentRequest(
        @NotBlank @Size(max = CreatorPostComment.MAX_CONTENT_LENGTH) String content
) {
}
