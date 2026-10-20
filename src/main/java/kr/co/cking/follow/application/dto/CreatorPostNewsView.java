package kr.co.cking.follow.application.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/** 홈의 크리에이터 새 글 표시를 위한 최소 조회 값. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CreatorPostNewsView(Long creatorId, boolean hasUnreadPosts, Instant latestPostCreatedAt) {
}
