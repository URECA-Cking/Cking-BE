package kr.co.cking.auth.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import kr.co.cking.auth.application.dto.RefreshTokenRotationResult;
import kr.co.cking.auth.domain.AuthErrorCode;
import kr.co.cking.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Redis에 hash key만 남기는 opaque Refresh Token의 발급·회전·폐기를 담당한다. */
@Service
public class RefreshTokenService {

    private static final String KEY_PREFIX = "auth:refresh-token:";
    private static final int TOKEN_BYTE_LENGTH = 32;

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<String> refreshTokenRotateScript;
    private final Duration refreshTokenTtl;
    private final SecureRandom secureRandom = new SecureRandom();

    /** Redis 회전 스크립트와 Refresh Token TTL을 생성자에서 명시적으로 주입받는다. */
    public RefreshTokenService(
            StringRedisTemplate redisTemplate,
            @Qualifier("refreshTokenRotateScript") DefaultRedisScript<String> refreshTokenRotateScript,
            @Value("${cking.auth.refresh-token-ttl:P14D}") Duration refreshTokenTtl
    ) {
        this.redisTemplate = redisTemplate;
        this.refreshTokenRotateScript = refreshTokenRotateScript;
        this.refreshTokenTtl = refreshTokenTtl;
    }

    /** 회원 ID에 연결된 새 opaque Refresh Token을 Redis hash key와 함께 저장한다. */
    public String issue(Long memberId) {
        Objects.requireNonNull(memberId, "memberId는 필수입니다.");
        String refreshToken = createToken();
        redisTemplate.opsForValue().set(redisKey(refreshToken), memberId.toString(), refreshTokenTtl);
        return refreshToken;
    }

    /** 기존 Token을 원자적으로 소비하고 다음 Refresh Token 및 연결 회원 ID를 반환한다. */
    public RefreshTokenRotationResult rotate(String refreshToken) {
        validateToken(refreshToken);
        String nextRefreshToken = createToken();
        String memberId = redisTemplate.execute(
                refreshTokenRotateScript,
                List.of(redisKey(refreshToken), redisKey(nextRefreshToken)),
                Long.toString(refreshTokenTtl.toMillis()));
        if (memberId == null) {
            throw new BusinessException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        }
        try {
            return new RefreshTokenRotationResult(Long.valueOf(memberId), nextRefreshToken);
        } catch (NumberFormatException exception) {
            throw new BusinessException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        }
    }

    /** 전달된 Token의 hash key만 삭제해 현재 브라우저 세션을 폐기한다. */
    public void revoke(String refreshToken) {
        if (StringUtils.hasText(refreshToken)) {
            redisTemplate.delete(redisKey(refreshToken));
        }
    }

    /** URL·Cookie 값으로 안전한 256비트 opaque Token을 생성한다. */
    private String createToken() {
        byte[] bytes = new byte[TOKEN_BYTE_LENGTH];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 원문 Token을 노출하지 않는 SHA-256 Redis key를 생성한다. */
    private String redisKey(String refreshToken) {
        return KEY_PREFIX + sha256(refreshToken);
    }

    /** 원문 값을 Redis key에 사용할 고정 길이 SHA-256 문자열로 변환한다. */
    private String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }

    /** 누락되거나 공백인 Cookie Token을 Auth 오류로 통합한다. */
    private void validateToken(String refreshToken) {
        if (!StringUtils.hasText(refreshToken)) {
            throw new BusinessException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        }
    }
}
