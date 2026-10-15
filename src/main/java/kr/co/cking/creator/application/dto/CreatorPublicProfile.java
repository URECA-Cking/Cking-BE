package kr.co.cking.creator.application.dto;

/**
 * 다른 도메인이 화면 표시용으로 쓰는 Creator 공개 프로필.
 *
 * @param profileImageUrl Creator Space의 프로필 이미지 URL. Space 행이 없으면 null이다.
 */
public record CreatorPublicProfile(Long creatorId, String name, String profileImageUrl) {
}
