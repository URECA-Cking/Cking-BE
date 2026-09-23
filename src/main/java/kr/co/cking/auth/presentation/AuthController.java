package kr.co.cking.auth.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cking.auth.application.AccessTokenService;
import kr.co.cking.auth.application.LoginCodeService;
import kr.co.cking.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** OAuth Login Code를 Cking Access Token으로 교환하는 인증 API를 제공한다. */
@RestController
@RequiredArgsConstructor
@Tag(name = "Auth", description = "OAuth Login Code 교환과 Access JWT 발급 API")
public class AuthController {

    private final LoginCodeService loginCodeService;
    private final AccessTokenService accessTokenService;

    /** 1회용 Login Code를 소비하고 해당 Member의 Access JWT를 발급한다. */
    @Operation(
            summary = "Access Token 발급",
            description = "1회용 Login Code를 원자적으로 소비해 30분 유효한 Bearer Access JWT를 발급합니다."
    )
    @PostMapping("/api/auth/token")
    public ApiResponse<TokenResponse> exchangeToken(@Valid @RequestBody TokenExchangeRequest request) {
        Long memberId = loginCodeService.consume(request.code());
        return ApiResponse.success(TokenResponse.from(accessTokenService.issue(memberId)));
    }
}
