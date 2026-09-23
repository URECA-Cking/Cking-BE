package kr.co.cking.auth.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cking.auth.application.AccessTokenService;
import kr.co.cking.auth.application.LoginCodeService;
import kr.co.cking.auth.application.RefreshTokenService;
import kr.co.cking.auth.application.dto.RefreshTokenRotationResult;
import kr.co.cking.auth.domain.AuthErrorCode;
import kr.co.cking.common.exception.ErrorCode;
import kr.co.cking.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** OAuth Login Code를 Cking Access Token으로 교환하는 인증 API를 제공한다. */
@RestController
@RequiredArgsConstructor
@Tag(name = "Auth", description = "OAuth Login Code 교환, Access JWT 갱신 및 Logout API")
public class AuthController {

    private final LoginCodeService loginCodeService;
    private final AccessTokenService accessTokenService;
    private final RefreshTokenService refreshTokenService;
    private final RefreshTokenCookieFactory refreshTokenCookieFactory;
    private final RefreshRequestOriginValidator refreshRequestOriginValidator;

    /** 1회용 Login Code를 소비하고 해당 Member의 Access JWT를 발급한다. */
    @Operation(
            summary = "Access Token 발급",
            description = "1회용 Login Code를 원자적으로 소비해 30분 유효한 Bearer Access JWT와 14일 Refresh Cookie를 발급합니다."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Access JWT와 Refresh Cookie 발급 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Login Code가 유효하지 않음")
    @PostMapping("/api/auth/token")
    public ResponseEntity<ApiResponse<TokenResponse>> exchangeToken(@Valid @RequestBody TokenExchangeRequest request) {
        Long memberId = loginCodeService.consume(request.code());
        return tokenResponse(memberId, refreshTokenService.issue(memberId));
    }

    /** Refresh Cookie를 한 번 소비하고 새 Access JWT와 Refresh Cookie를 발급한다. */
    @Operation(
            summary = "Access Token 갱신",
            description = "Refresh Cookie를 원자적으로 회전해 새 Bearer Access JWT와 Refresh Cookie를 발급합니다."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Access JWT 갱신 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Refresh Token이 유효하지 않음")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "허용되지 않은 Origin")
    @PostMapping("/api/auth/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(
            @CookieValue(name = "refresh_token", required = false) String refreshToken,
            @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin
    ) {
        refreshRequestOriginValidator.validate(origin);
        RefreshTokenRotationResult result = refreshTokenService.rotate(refreshToken);
        try {
            return tokenResponse(result.memberId(), result.refreshToken(), AuthErrorCode.INVALID_REFRESH_TOKEN);
        } catch (RuntimeException exception) {
            // 응답 생성이 실패하면 클라이언트에 전달되지 않은 다음 Token을 폐기해 고아 key를 남기지 않는다.
            refreshTokenService.revoke(result.refreshToken());
            throw exception;
        }
    }

    /** 현재 브라우저의 Refresh Token을 폐기하고 만료 Cookie를 응답한다. */
    @Operation(
            summary = "Logout",
            description = "Refresh Token을 Redis에서 폐기하고 브라우저의 Refresh Cookie를 만료합니다."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Logout 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "허용되지 않은 Origin")
    @PostMapping("/api/auth/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @CookieValue(name = "refresh_token", required = false) String refreshToken,
            @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin
    ) {
        refreshRequestOriginValidator.validate(origin);
        refreshTokenService.revoke(refreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookieFactory.expire().toString())
                .body(ApiResponse.success());
    }

    /** Access JWT 응답과 동일한 속성의 새 Refresh Cookie를 함께 반환한다. */
    private ResponseEntity<ApiResponse<TokenResponse>> tokenResponse(Long memberId, String refreshToken) {
        return tokenResponse(memberId, refreshToken, AuthErrorCode.INVALID_LOGIN_CODE);
    }

    /** 호출한 인증 수단의 오류 계약에 맞춰 Access JWT와 Refresh Cookie를 함께 반환한다. */
    private ResponseEntity<ApiResponse<TokenResponse>> tokenResponse(
            Long memberId, String refreshToken, ErrorCode invalidCredentialError
    ) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookieFactory.create(refreshToken).toString())
                .body(ApiResponse.success(TokenResponse.from(accessTokenService.issue(memberId, invalidCredentialError))));
    }
}
