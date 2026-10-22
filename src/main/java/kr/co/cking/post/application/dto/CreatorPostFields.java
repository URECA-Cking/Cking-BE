package kr.co.cking.post.application.dto;

import kr.co.cking.post.domain.PostVisibility;

import java.util.List;

/**
 * 게시글 작성·수정 입력. 수정도 부분 수정이 아니라 모든 필드를 새 값으로 교체한다.
 *
 * @param imageKeys 업로드 API로 받은 이미지 key. 순서가 곧 표시 순서다. null이면 이미지 없음.
 */
public record CreatorPostFields(String content, PostVisibility visibility, List<String> imageKeys) {
}
