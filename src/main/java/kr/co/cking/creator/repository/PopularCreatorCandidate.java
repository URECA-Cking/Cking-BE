package kr.co.cking.creator.repository;

/** 팔로워 수 순으로 읽은 공개 Creator 카드 한 행이다. */
public record PopularCreatorCandidate(
        Long creatorId,
        String creatorName,
        String introText,
        String profileImageUrl,
        long followerCount
) {
}
