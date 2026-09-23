package kr.co.cking.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import kr.co.cking.auth.application.dto.RefreshTokenRotationResult;
import kr.co.cking.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 실제 Redis Lua 실행에서 Refresh Token rotation이 한 번만 성공하는지 검증한다. */
@SpringBootTest
class RefreshTokenServiceIntegrationTest {

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /** Redis에는 원문 Token 대신 SHA-256 hash key만 남는지 검증한다. */
    @Test
    void RefreshToken은_Hash_key로만_저장된다() throws Exception {
        String refreshToken = refreshTokenService.issue(91L);

        assertThat(redisTemplate.opsForValue().get("auth:refresh-token:" + sha256(refreshToken))).isEqualTo("91");
        assertThat(redisTemplate.opsForValue().get("auth:refresh-token:" + refreshToken)).isNull();
    }

    /** Logout용 폐기가 Redis의 hash key를 삭제해 이후 사용을 막는지 검증한다. */
    @Test
    void RefreshToken을_폐기하면_Redis에서_삭제된다() throws Exception {
        String refreshToken = refreshTokenService.issue(91L);

        refreshTokenService.revoke(refreshToken);

        assertThat(redisTemplate.opsForValue().get("auth:refresh-token:" + sha256(refreshToken))).isNull();
    }

    /** 같은 Refresh Token을 동시에 갱신해도 하나의 요청만 다음 Token을 받는지 검증한다. */
    @Test
    void 동일_RefreshToken은_동시_회전해도_한번만_성공한다() throws Exception {
        String refreshToken = refreshTokenService.issue(91L);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<RefreshTokenRotationResult> results = completedResults(executor.invokeAll(List.of(
                    rotate(refreshToken), rotate(refreshToken))));

            assertThat(results).hasSize(1);
            assertThat(results.getFirst().memberId()).isEqualTo(91L);
            assertThat(results.getFirst().refreshToken()).isNotEqualTo(refreshToken);
            assertThatThrownBy(() -> refreshTokenService.rotate(refreshToken))
                    .isInstanceOf(BusinessException.class);
        }
    }

    /** rotation 성공 결과 또는 유효하지 않은 Token의 실패를 병렬 작업으로 만든다. */
    private Callable<RefreshTokenRotationResult> rotate(String refreshToken) {
        return () -> {
            try {
                return refreshTokenService.rotate(refreshToken);
            } catch (BusinessException exception) {
                return null;
            }
        };
    }

    /** 병렬 요청 중 실제로 rotation에 성공한 결과만 수집한다. */
    private List<RefreshTokenRotationResult> completedResults(List<Future<RefreshTokenRotationResult>> futures)
            throws Exception {
        java.util.ArrayList<RefreshTokenRotationResult> results = new java.util.ArrayList<>();
        for (Future<RefreshTokenRotationResult> future : futures) {
            RefreshTokenRotationResult result = future.get();
            if (result != null) {
                results.add(result);
            }
        }
        return results;
    }

    /** 테스트에서 Redis hash key를 재현하기 위해 SHA-256 문자열을 계산한다. */
    private String sha256(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(hash);
    }
}
