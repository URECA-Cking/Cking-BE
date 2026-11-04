package kr.co.cking.subscriptionverification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

class YoutubeChannelConnectionServiceTest {

    private static final Long MEMBER_ID = 42L;
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final YoutubeChannelOwnershipClient provider = mock(YoutubeChannelOwnershipClient.class);
    private final CreatorYoutubeChannelService channels = mock(CreatorYoutubeChannelService.class);
    private final YoutubeChannelConnectionService service = new YoutubeChannelConnectionService(
            redis, JsonMapper.builder().build(), provider, channels);

    @BeforeEach
    void setUp() {
        given(redis.opsForValue()).willReturn(values);
        given(provider.authorizationUrl(any(), any())).willReturn("https://accounts.google.com/test");
        given(channels.creatorIdForConnection(MEMBER_ID)).willReturn(7L);
    }

    @Test
    void 유효한_브라우저_바인딩과_state는_한_번만_채널을_연결한다() {
        AtomicReference<String> saved = new AtomicReference<>();
        given(values.getAndDelete(any())).willAnswer(invocation -> saved.getAndSet(null));
        org.mockito.Mockito.doAnswer(invocation -> {
            saved.set(invocation.getArgument(1));
            return null;
        }).when(values).set(any(), any(), eq(Duration.ofMinutes(10)));
        given(provider.verify(eq("google-code"), any(), eq("@creator")))
                .willReturn(new YoutubeChannelOwnershipClient.OwnedChannel(
                        "UCabcdefghijklmnopqrstuv", "Creator", "@creator"));

        YoutubeChannelConnectionService.ConnectionStart started = service.start(MEMBER_ID, "@Creator");
        ArgumentCaptor<String> stateCaptor = ArgumentCaptor.forClass(String.class);
        then(provider).should().authorizationUrl(stateCaptor.capture(), any());
        String state = stateCaptor.getValue();
        service.complete(state, started.browserBinding(), "google-code");

        then(channels).should().connectVerified(MEMBER_ID, 7L,
                "UCabcdefghijklmnopqrstuv", "Creator", "@creator");
        assertThatThrownBy(() -> service.complete(state, started.browserBinding(), "google-code"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(SubscriptionVerificationErrorCode.CHANNEL_CONNECTION_INVALID);
    }

    @Test
    void 다른_브라우저의_쿠키로는_연결을_완료할_수_없다() {
        YoutubeChannelConnectionService.ConnectionStart started = service.start(MEMBER_ID, "@Creator");
        ArgumentCaptor<String> stateCaptor = ArgumentCaptor.forClass(String.class);
        then(provider).should().authorizationUrl(stateCaptor.capture(), any());

        assertThat(started.browserBinding()).isNotBlank();
        assertThatThrownBy(() -> service.complete(stateCaptor.getValue(),
                "b".repeat(43), "google-code"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(SubscriptionVerificationErrorCode.CHANNEL_CONNECTION_INVALID);
        then(channels).should(never()).connectVerified(any(), any(), any(), any(), any());
    }

    @Test
    void 만료되거나_변조된_state는_채널을_연결하지_않는다() {
        YoutubeChannelConnectionService.ConnectionStart started = service.start(MEMBER_ID, "@Creator");
        assertThatThrownBy(() -> service.complete("a".repeat(43),
                started.browserBinding(), "google-code"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(SubscriptionVerificationErrorCode.CHANNEL_CONNECTION_INVALID);
        then(channels).should(never()).connectVerified(any(), any(), any(), any(), any());
    }
}
