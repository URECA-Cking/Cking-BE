package kr.co.cking.follow.repository;

/** Creator 한 명의 팔로워 수 집계 한 행이다. 팔로워가 없는 Creator는 집계 결과에 나타나지 않는다. */
public record CreatorFollowerCount(Long creatorId, long followerCount) {
}
