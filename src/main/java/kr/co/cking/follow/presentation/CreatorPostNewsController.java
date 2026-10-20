package kr.co.cking.follow.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.follow.application.CreatorPostNewsService;
import kr.co.cking.follow.application.dto.CreatorPostNewsView;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/** 인증된 회원의 크리에이터 게시글 읽음 상태 API. */
@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "크리에이터 새 소식", description = "팔로우한 크리에이터의 게시글 읽음 기록과 새 글 조회 API를 제공합니다.")
public class CreatorPostNewsController {

    private final CreatorPostNewsService newsService;

    @Operation(summary = "크리에이터 게시글 읽음 기록",
            description = "현재 팔로우 중인 크리에이터의 글을 본 뒤 호출합니다. 서버 시각으로 읽음 시각을 앞으로만 옮깁니다.")
    @PutMapping("/api/creators/{creatorId}/posts/read")
    public ApiResponse<ReadStatus> markRead(@CurrentMemberId Long memberId, @PathVariable @Positive Long creatorId) {
        Instant lastReadAt = newsService.markRead(memberId, creatorId);
        return ApiResponse.success(new ReadStatus(creatorId, lastReadAt));
    }

    @Operation(summary = "팔로우한 크리에이터 새 글 일괄 조회",
            description = "현재 팔로우한 모든 크리에이터의 볼 수 있는 최신 글과 읽지 않은 글 여부를 조회합니다.")
    @GetMapping("/api/me/follows/post-news")
    public ApiResponse<PostNews> findMine(@CurrentMemberId Long memberId) {
        return ApiResponse.success(new PostNews(newsService.findMine(memberId)));
    }

    public record ReadStatus(Long creatorId, Instant lastReadAt) {
    }

    public record PostNews(List<CreatorPostNewsView> items) {
    }
}
