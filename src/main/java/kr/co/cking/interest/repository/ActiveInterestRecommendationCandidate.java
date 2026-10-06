package kr.co.cking.interest.repository;

/** 회원이 고른 관심 분야의 현재 활성 추천 세대에서 일괄 조회한 후보 행이다. {@code rank}는 원래 순위다. */
public record ActiveInterestRecommendationCandidate(String interestCode, Long creatorId, int rank) {
}
