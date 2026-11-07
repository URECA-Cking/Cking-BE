package kr.co.cking.subscriptionverification.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.subscriptionverification.domain.CreatorYoutubeChannel;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** 연결을 시작한 Creator와 브라우저에 묶인 일회용 OAuth 상태를 관리한다. */
@Service
@RequiredArgsConstructor
public class YoutubeChannelConnectionService {

    private static final String KEY_PREFIX = "creator:youtube-connection:";
    public static final Duration TTL = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;
    private final YoutubeChannelOwnershipClient ownershipClient;
    private final CreatorYoutubeChannelService channelService;

    public ConnectionStart start(Long memberId, String requestedHandle) {
        Long creatorId = channelService.creatorIdForConnection(memberId);
        String handle;
        try {
            handle = CreatorYoutubeChannel.normalizeHandle(requestedHandle);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(SubscriptionVerificationErrorCode.CHANNEL_CONNECTION_INVALID);
        }
        String state = randomValue();
        String binding = randomValue();
        String verifier = randomValue();
        String url = ownershipClient.authorizationUrl(state, sha256Base64(verifier));
        try {
            redisTemplate.opsForValue().set(key(state, binding),
                    jsonMapper.writeValueAsString(new PendingConnection(memberId, creatorId, handle, verifier)), TTL);
        } catch (DataAccessException exception) {
            throw new BusinessException(SubscriptionVerificationErrorCode.CHANNEL_CONNECTION_UNAVAILABLE);
        }
        return new ConnectionStart(url, state, binding);
    }

    public void complete(String state, String binding, String authorizationCode) {
        PendingConnection pending = consume(state, binding);
        if (authorizationCode == null || authorizationCode.isBlank()) {
            throw new BusinessException(SubscriptionVerificationErrorCode.CHANNEL_CONNECTION_INVALID);
        }
        YoutubeChannelOwnershipClient.OwnedChannel owned = ownershipClient.verify(
                authorizationCode, pending.verifier(), pending.handle());
        channelService.connectVerified(pending.memberId(), pending.creatorId(), owned.channelId(),
                owned.channelName(), owned.channelHandle());
    }

    public void cancel(String state, String binding) {
        consume(state, binding);
    }

    private PendingConnection consume(String state, String binding) {
        if (state == null || !state.matches("[A-Za-z0-9_-]{43}")
                || binding == null || !binding.matches("[A-Za-z0-9_-]{43}")) {
            throw new BusinessException(SubscriptionVerificationErrorCode.CHANNEL_CONNECTION_INVALID);
        }
        String json;
        try {
            json = redisTemplate.opsForValue().getAndDelete(key(state, binding));
        } catch (DataAccessException exception) {
            throw new BusinessException(SubscriptionVerificationErrorCode.CHANNEL_CONNECTION_UNAVAILABLE);
        }
        if (json == null) {
            throw new BusinessException(SubscriptionVerificationErrorCode.CHANNEL_CONNECTION_INVALID);
        }
        try {
            PendingConnection pending = jsonMapper.readValue(json, PendingConnection.class);
            if (pending.memberId() == null || pending.creatorId() == null
                    || pending.handle() == null || pending.verifier() == null) {
                throw new IllegalArgumentException("Missing OAuth state field");
            }
            return pending;
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new BusinessException(SubscriptionVerificationErrorCode.CHANNEL_CONNECTION_INVALID);
        }
    }

    private String key(String state, String binding) {
        return KEY_PREFIX + sha256Base64(binding) + ":" + state;
    }

    private static String randomValue() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Base64(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record PendingConnection(Long memberId, Long creatorId, String handle, String verifier) {
    }

    public record ConnectionStart(String authorizationUrl, String state, String browserBinding) {
    }
}
