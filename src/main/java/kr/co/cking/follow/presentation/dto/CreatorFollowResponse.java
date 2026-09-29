package kr.co.cking.follow.presentation.dto;

import kr.co.cking.follow.application.dto.FollowedCreatorView;

import java.time.Instant;

public final class CreatorFollowResponse {

    private CreatorFollowResponse() {
    }

    /** 특정 크리에이터에 대한 호출자의 팔로우 여부. */
    public record Status(Long creatorId, boolean following) {
    }

    /** 내가 팔로우한 크리에이터 한 건. */
    public record Followed(Long creatorId, String creatorName, Instant followedAt) {

        public static Followed from(FollowedCreatorView view) {
            return new Followed(view.creatorId(), view.creatorName(), view.followedAt());
        }
    }
}
