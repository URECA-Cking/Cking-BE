package kr.co.cking.creator.repository;

/** Creator Space의 소개 원문만 읽은 값이다. 엔티티를 거치지 않아 같은 트랜잭션에서 이미 불러온 값이 아니라 DB의 현재 값이다. */
public record CreatorIntroText(Long creatorId, String introText) {
}
