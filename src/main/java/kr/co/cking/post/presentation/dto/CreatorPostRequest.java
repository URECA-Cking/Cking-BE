package kr.co.cking.post.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.co.cking.post.application.dto.CreatorPostFields;
import kr.co.cking.post.domain.CreatorPost;
import kr.co.cking.post.domain.PostVisibility;

import java.util.List;

public final class CreatorPostRequest {

    private CreatorPostRequest() {
    }

    /**
     * 게시글 작성·수정 요청. 수정도 모든 필드를 새 값으로 교체한다.
     *
     * @param imageKeys 이미지 업로드 API로 받은 key. 배열 순서가 표시 순서다.
     */
    public record Save(
            @Size(max = CreatorPost.MAX_CONTENT_LENGTH) String content,
            @NotNull PostVisibility visibility,
            @Size(max = CreatorPost.MAX_IMAGE_COUNT) List<@NotBlank @Size(max = 200) String> imageKeys
    ) {

        public CreatorPostFields toFields() {
            return new CreatorPostFields(content, visibility, imageKeys);
        }
    }
}
