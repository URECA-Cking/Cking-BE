package kr.co.cking.subscriptionverification.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.subscriptionverification.application.CreatorYoutubeChannelCommand;
import kr.co.cking.subscriptionverification.application.CreatorYoutubeChannelService;
import kr.co.cking.subscriptionverification.application.CreatorYoutubeChannelUpsertResult;
import kr.co.cking.subscriptionverification.presentation.dto.CreatorYoutubeChannelRequest;
import kr.co.cking.subscriptionverification.presentation.dto.CreatorYoutubeChannelResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "Creator YouTube 채널", description = "Creator 구독 인증 대상 채널 설정 API")
public class CreatorYoutubeChannelController {

    private final CreatorYoutubeChannelService channelService;

    @GetMapping("/api/creator/youtube-channel")
    @Operation(summary = "내 YouTube 채널 설정 조회")
    public ApiResponse<CreatorYoutubeChannelResponse> getMine(@CurrentMemberId Long memberId) {
        return ApiResponse.success(CreatorYoutubeChannelResponse.from(channelService.getMine(memberId)));
    }

    @PutMapping("/api/creator/youtube-channel")
    @Operation(summary = "YouTube 채널 최초 설정 또는 전체 교체")
    public ResponseEntity<ApiResponse<CreatorYoutubeChannelResponse>> put(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody CreatorYoutubeChannelRequest.Put request
    ) {
        CreatorYoutubeChannelUpsertResult result = channelService.put(
                memberId, new CreatorYoutubeChannelCommand(request.channelName(), request.channelHandle()));
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .body(ApiResponse.success(CreatorYoutubeChannelResponse.from(result.channel())));
    }
}
