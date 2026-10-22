package kr.co.cking.auth.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import kr.co.cking.auth.application.dto.IssuedLoginCode;
import kr.co.cking.auth.domain.AuthErrorCode;
import kr.co.cking.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/** OAuth 완료 결과를 Frontend에 전달할 1회용 Login Code를 관리한다. */
@Service
@RequiredArgsConstructor
public class LoginCodeService {

    private static final String KEY_PREFIX = "auth:login-code:";
    public static final Duration TTL = Duration.ofSeconds(60);
    private static final int CODE_BYTE_LENGTH = 32;

    private final StringRedisTemplate redisTemplate;
    @Qualifier("loginCodeConsumeScript")
    private final DefaultRedisScript<String> loginCodeConsumeScript;
    private final SecureRandom secureRandom = new SecureRandom();

    /** 회원 ID를 교환할 새 Login Code와 브라우저 값을 만들고, 둘 다 해시로만 Redis에 저장한다. */
    public IssuedLoginCode issue(Long memberId) {
        Objects.requireNonNull(memberId, "memberId는 필수입니다.");
        String code = createCode();
        String browserBinding = createCode();
        redisTemplate.opsForValue().set(redisKey(code), memberId + ":" + sha256(browserBinding), TTL);
        return new IssuedLoginCode(code, browserBinding);
    }

    /** 발급받은 브라우저에서 온 Code만 원자적으로 한 번 소비하고 연결된 회원 ID를 반환한다. */
    public Long consume(String code, String browserBinding) {
        if (code == null || code.isBlank() || browserBinding == null || browserBinding.isBlank()) {
            throw new BusinessException(AuthErrorCode.INVALID_LOGIN_CODE);
        }
        String memberId = redisTemplate.execute(
                loginCodeConsumeScript, List.of(redisKey(code)), sha256(browserBinding));
        if (memberId == null) {
            throw new BusinessException(AuthErrorCode.INVALID_LOGIN_CODE);
        }

        try {
            return Long.valueOf(memberId);
        } catch (NumberFormatException exception) {
            throw new BusinessException(AuthErrorCode.INVALID_LOGIN_CODE);
        }
    }

    /** SecureRandom으로 URL query parameter에 안전한 Login Code 원문을 만든다. */
    private String createCode() {
        byte[] bytes = new byte[CODE_BYTE_LENGTH];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 원문 Code를 노출하지 않는 SHA-256 기반 Redis key를 만든다. */
    private String redisKey(String code) {
        return KEY_PREFIX + sha256(code);
    }

    /** 원문을 고정 길이 SHA-256 16진수 문자열로 변환한다. */
    private String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }
}
