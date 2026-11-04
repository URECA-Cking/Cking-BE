package kr.co.cking.subscriptionverification.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.time.Duration;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.response.ApiResponse;
import kr.co.cking.common.security.CurrentMemberId;
import kr.co.cking.subscriptionverification.application.CreatorYoutubeChannelService;
import kr.co.cking.subscriptionverification.application.YoutubeChannelConnectionService;
import kr.co.cking.subscriptionverification.domain.CreatorYoutubeChannel;
import kr.co.cking.subscriptionverification.presentation.dto.CreatorYoutubeChannelResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.WebUtils;

@RestController
@RequiredArgsConstructor
@Tag(name = "Creator YouTube 채널", description = "YouTube OAuth 채널 소유권 검증")
public class YoutubeChannelConnectionController {

    private static final String BINDING_COOKIE = "cking_youtube_connection";
    private static final String CALLBACK_PATH = "/login/oauth2/code/youtube";

    private final YoutubeChannelConnectionService connectionService;
    private final CreatorYoutubeChannelService channelService;

    @Value("${cking.auth.refresh-cookie-secure:false}")
    private boolean secureCookie;

    @Value("${cking.youtube-oauth.frontend-result-url:http://localhost:5173/my-page}")
    private String frontendResultUrl;

    @PostMapping("/api/creator/youtube-channel/connections")
    @Operation(summary = "YouTube 채널 소유권 검증 시작")
    public ApiResponse<StartResponse> start(@CurrentMemberId Long memberId,
            @Valid @RequestBody StartRequest request, HttpServletResponse response) {
        YoutubeChannelConnectionService.ConnectionStart start =
                connectionService.start(memberId, request.channelHandle());
        writeCookie(response, start.browserBinding(), YoutubeChannelConnectionService.TTL);
        return ApiResponse.success(new StartResponse(start.authorizationUrl()));
    }

    @GetMapping("/api/creator/youtube-channel/connection")
    @Operation(summary = "내 YouTube 채널 소유권 검증 상태")
    public ApiResponse<ConnectionStatusResponse> status(@CurrentMemberId Long memberId) {
        CreatorYoutubeChannel channel = channelService.getMineOrNull(memberId);
        return ApiResponse.success(channel == null
                ? new ConnectionStatusResponse("NOT_CONNECTED", null)
                : new ConnectionStatusResponse(channel.getVerificationStatus().name(),
                        CreatorYoutubeChannelResponse.from(channel)));
    }

    @GetMapping(CALLBACK_PATH)
    @Operation(summary = "Google YouTube 채널 소유권 검증 콜백")
    public void callback(@RequestParam(required = false) String state,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        var cookie = WebUtils.getCookie(request, BINDING_COOKIE);
        String binding = cookie == null ? null : cookie.getValue();
        writeCookie(response, "", Duration.ZERO);
        String result;
        try {
            if (error != null) {
                connectionService.cancel(state, binding);
                result = "cancelled";
            } else {
                connectionService.complete(state, binding, code);
                result = "success";
            }
        } catch (BusinessException exception) {
            result = switch (exception.getErrorCode().code()) {
                case "CHANNEL_ALREADY_CONNECTED" -> "conflict";
                case "CHANNEL_CONNECTION_MISMATCH" -> "mismatch";
                case "CHANNEL_CONNECTION_UNAVAILABLE" -> "unavailable";
                case "INVALID_STATE" -> "in_progress";
                default -> "invalid";
            };
        }
        response.sendRedirect(frontendResultUrl + (frontendResultUrl.contains("?") ? "&" : "?")
                + "youtubeConnection=" + result);
    }

    private void writeCookie(HttpServletResponse response, String binding, Duration maxAge) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(BINDING_COOKIE, binding)
                .httpOnly(true).secure(secureCookie).sameSite("Lax")
                .path(CALLBACK_PATH).maxAge(maxAge).build().toString());
    }

    public record StartRequest(@NotBlank String channelHandle) {
    }

    public record StartResponse(String authorizationUrl) {
    }

    public record ConnectionStatusResponse(String verificationStatus, CreatorYoutubeChannelResponse channel) {
    }
}
