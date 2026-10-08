package kr.co.cking.common.event;

import java.time.Instant;
import java.util.UUID;

/** 도메인 간에는 ID와 원본 전환 시각만 전달한다. 영속 이벤트와 같은 ID를 사용한다. */
public record CreatorFollowCreated(UUID eventId, Long memberId, Long creatorId, Instant followedAt) { }
