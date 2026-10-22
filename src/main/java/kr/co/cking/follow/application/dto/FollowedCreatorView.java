package kr.co.cking.follow.application.dto;

import java.time.Instant;

/** 내가 팔로우한 크리에이터 한 건. */
public record FollowedCreatorView(Long creatorId, String creatorName, Instant followedAt) {
}
