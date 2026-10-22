package kr.co.cking.subscriptionverification.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.subscriptionverification.application.CreatorYoutubeChannelService;
import kr.co.cking.subscriptionverification.presentation.dto.CreatorYoutubeChannelResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "Creator YouTube 채널 공개 조회", description = "Creator 구독 인증 대상 채널 공개 API")
public class PublicCreatorYoutubeChannelController {

    private final CreatorYoutubeChannelService channelService;

    @GetMapping("/api/creators/{creatorId}/youtube-channel")
    @Operation(summary = "Creator YouTube 채널 공개 조회")
    public ApiResponse<CreatorYoutubeChannelResponse> getPublic(@PathVariable @Positive Long creatorId) {
        return ApiResponse.success(CreatorYoutubeChannelResponse.from(channelService.getPublic(creatorId)));
    }
}
