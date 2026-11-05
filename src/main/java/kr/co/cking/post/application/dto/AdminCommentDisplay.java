package kr.co.cking.post.application.dto;

/**
 * 관리자 댓글 목록이 ID와 함께 보여주는 표시 값.
 *
 * @param creatorId   스페이스 Creator ID. 게시글을 찾지 못하면 null이다
 * @param creatorName 스페이스 Creator의 활동명. Creator를 찾지 못하면 null이다
 * @param postPreview 게시글 본문 앞부분. 게시글에 제목이 없어 대신 보여준다
 * @param authorName  작성자 표시 이름. Creator 계정은 활동명, 그 밖의 회원은 닉네임이고 회원을 찾지 못하면 null이다
 */
public record AdminCommentDisplay(Long creatorId, String creatorName, String postPreview, String authorName) {
}
