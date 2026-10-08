package kr.co.cking.follow.application;

import java.time.Instant;

/** 실제 새 관계가 생성된 경우에만 발행하며 수신자는 커밋 이후에 처리한다. */
public record CreatorFollowCreated(Long memberId, Long creatorId, Instant followedAt) {
}
