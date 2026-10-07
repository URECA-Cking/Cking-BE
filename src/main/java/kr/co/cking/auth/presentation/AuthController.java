package kr.co.cking.auth.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cking.auth.application.AdminAuthService;
import kr.co.cking.auth.application.AccessTokenService;
import kr.co.cking.auth.application.LoginCodeService;
import kr.co.cking.auth.application.RefreshTokenService;
import kr.co.cking.auth.application.dto.RefreshTokenRotationResult;
import kr.co.cking.auth.domain.AuthErrorCode;
import kr.co.cking.auth.domain.RefreshSessionType;
import kr.co.cking.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** OAuth Login Code를 Cking Access Token으로 교환하는 인증 API를 제공한다. */
@RestController
@Slf4j
@RequiredArgsConstructor
@Tag(name = "Auth", description = "OAuth Login Code 교환, 관리자 로그인, Access JWT 갱신 및 Logout API")
public class AuthController {

    private final AdminAuthService adminAuthService;
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
        String refreshToken = refreshTokenService.issue(memberId, RefreshSessionType.USER_WEB);
        try {
            return tokenResponse(memberId, refreshToken, AuthErrorCode.INVALID_LOGIN_CODE, RefreshSessionType.USER_WEB);
        } catch (RuntimeException exception) {
            // 응답 생성이 실패하면 클라이언트에 전달되지 않은 Refresh Token을 폐기해 고아 key를 남기지 않는다.
            revokeUnsentRefreshToken(refreshToken, exception);
            throw exception;
        }
    }

    /** 관리자 ID/PW를 검증하고 기존 Access JWT와 Refresh Cookie를 함께 발급한다. */
    @Operation(
            summary = "관리자 로그인",
            description = "활성 관리자 계정의 ID/PW를 검증해 기존 Bearer Access JWT와 14일 Refresh Cookie를 발급합니다."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "관리자 Access JWT와 Refresh Cookie 발급 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "관리자 ID/PW 또는 연결된 관리자 Member가 유효하지 않음")
    @PostMapping("/api/auth/admin/login")
    public ResponseEntity<ApiResponse<TokenResponse>> loginAdmin(@Valid @RequestBody AdminLoginRequest request) {
        Long memberId = adminAuthService.authenticate(request.loginId(), request.password());
        String refreshToken = refreshTokenService.issue(memberId, RefreshSessionType.ADMIN_WEB);
        try {
            return tokenResponse(memberId, refreshToken, AuthErrorCode.INVALID_ADMIN_CREDENTIALS, RefreshSessionType.ADMIN_WEB);
        } catch (RuntimeException exception) {
            // 응답 생성이 실패하면 클라이언트에 전달되지 않은 Refresh Token을 폐기해 고아 key를 남기지 않는다.
            revokeUnsentRefreshToken(refreshToken, exception);
            throw exception;
        }
    }

    /** 사용자 Web Refresh Cookie를 한 번 소비하고 새 Access JWT와 Refresh Cookie를 발급한다. */
    @Operation(
            summary = "Access Token 갱신",
            description = "사용자 Web Refresh Cookie를 원자적으로 회전해 새 Bearer Access JWT와 Refresh Cookie를 발급합니다."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Access JWT 갱신 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Refresh Token이 유효하지 않음")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "허용되지 않은 Origin")
    @PostMapping("/api/auth/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(
            @CookieValue(name = "refresh_token", required = false) String refreshToken,
            @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin
    ) {
        refreshRequestOriginValidator.validate(origin, RefreshSessionType.USER_WEB);
        RefreshTokenRotationResult result = refreshTokenService.rotate(refreshToken, RefreshSessionType.USER_WEB);
        try {
            return tokenResponse(result.memberId(), result.refreshToken(), AuthErrorCode.INVALID_REFRESH_TOKEN,
                    RefreshSessionType.USER_WEB);
        } catch (RuntimeException exception) {
            // 응답 생성이 실패하면 클라이언트에 전달되지 않은 다음 Token을 폐기해 고아 key를 남기지 않는다.
            revokeUnsentRefreshToken(result.refreshToken(), exception);
            throw exception;
        }
    }

    /** 현재 사용자 Web 브라우저의 Refresh Token을 폐기하고 만료 Cookie를 응답한다. */
    @Operation(
            summary = "Logout",
            description = "사용자 Web Refresh Token을 Redis에서 폐기하고 브라우저의 Refresh Cookie를 만료합니다."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Logout 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "허용되지 않은 Origin")
    @PostMapping("/api/auth/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @CookieValue(name = "refresh_token", required = false) String refreshToken,
            @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin
    ) {
        refreshRequestOriginValidator.validate(origin, RefreshSessionType.USER_WEB);
        refreshTokenService.revoke(refreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookieFactory.expire(RefreshSessionType.USER_WEB).toString())
                .body(ApiResponse.success());
    }

    /** 관리자 Web Refresh Cookie를 한 번 소비하고 ADMIN Access JWT와 다음 관리자 Cookie를 발급한다. */
    @Operation(
            summary = "관리자 Access Token 갱신",
            description = "관리자 Web Refresh Cookie를 원자적으로 회전해 ADMIN Bearer Access JWT와 다음 관리자 Refresh Cookie를 발급합니다."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "관리자 Access JWT 갱신 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "관리자 Refresh Token이 유효하지 않음")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 Web Origin이 아님")
    @PostMapping("/api/admin/auth/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refreshAdmin(
            @CookieValue(name = "admin_refresh_token", required = false) String refreshToken,
            @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin
    ) {
        refreshRequestOriginValidator.validate(origin, RefreshSessionType.ADMIN_WEB);
        RefreshTokenRotationResult result = refreshTokenService.rotate(refreshToken, RefreshSessionType.ADMIN_WEB);
        try {
            return tokenResponse(result.memberId(), result.refreshToken(), AuthErrorCode.INVALID_REFRESH_TOKEN,
                    RefreshSessionType.ADMIN_WEB);
        } catch (RuntimeException exception) {
            revokeUnsentRefreshToken(result.refreshToken(), exception);
            throw exception;
        }
    }

    /** 현재 관리자 Web 브라우저의 Refresh Token을 폐기하고 관리자 Cookie를 만료한다. */
    @Operation(
            summary = "관리자 Logout",
            description = "관리자 Refresh Token을 Redis에서 폐기하고 브라우저의 관리자 Refresh Cookie를 만료합니다."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "관리자 Logout 성공")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "관리자 Web Origin이 아님")
    @PostMapping("/api/admin/auth/logout")
    public ResponseEntity<ApiResponse<Void>> logoutAdmin(
            @CookieValue(name = "admin_refresh_token", required = false) String refreshToken,
            @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin
    ) {
        refreshRequestOriginValidator.validate(origin, RefreshSessionType.ADMIN_WEB);
        refreshTokenService.revoke(refreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookieFactory.expire(RefreshSessionType.ADMIN_WEB).toString())
                .body(ApiResponse.success());
    }

    /** 호출한 인증 수단의 오류 계약에 맞춰 Access JWT와 Refresh Cookie를 함께 반환한다. */
    private ResponseEntity<ApiResponse<TokenResponse>> tokenResponse(
            Long memberId, String refreshToken, AuthErrorCode invalidCredentialError, RefreshSessionType sessionType
    ) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookieFactory.create(sessionType, refreshToken).toString())
                .body(ApiResponse.success(TokenResponse.from(issueAccessToken(memberId, invalidCredentialError, sessionType))));
    }

    /** 관리자 세션은 ADMIN Member만, 사용자 세션은 기존 인증 수단 오류 계약으로 Access JWT를 발급한다. */
    private kr.co.cking.auth.application.dto.AccessTokenResult issueAccessToken(
            Long memberId, AuthErrorCode invalidCredentialError, RefreshSessionType sessionType
    ) {
        if (sessionType == RefreshSessionType.ADMIN_WEB) {
            return accessTokenService.issueAdmin(memberId, invalidCredentialError);
        }
        return accessTokenService.issue(memberId, invalidCredentialError);
    }

    /** 응답에 실리지 못한 Refresh Token을 폐기하되, 폐기 실패가 원래 예외를 가리지 않게 한다. */
    private void revokeUnsentRefreshToken(String refreshToken, RuntimeException originalException) {
        try {
            refreshTokenService.revoke(refreshToken);
        } catch (RuntimeException cleanupException) {
            originalException.addSuppressed(cleanupException);
            log.warn("미전달 Refresh Token 폐기에 실패했습니다.", cleanupException);
        }
    }
}
