package kr.co.cking.post.application.dto;

/**
 * 게시글 좋아요·취소 결과(이슈 #554).
 *
 * @param liked          요청 뒤 조회자의 좋아요 상태
 * @param likeCount      요청 뒤 게시글의 좋아요 수
 * @param missionAwarded 이번 요청으로 좋아요 미션 응모권이 새로 적립되면 true
 */
public record PostLikeView(Long postId, boolean liked, long likeCount, boolean missionAwarded) {
}
