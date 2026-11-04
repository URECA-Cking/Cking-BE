package kr.co.cking.subscriptionverification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.Instant;
import java.util.Optional;
import kr.co.cking.subscriptionverification.domain.CreatorYoutubeChannel;
import kr.co.cking.subscriptionverification.repository.CreatorYoutubeChannelRepository;
import org.junit.jupiter.api.Test;

class SubscriptionVerificationChannelIdentityServiceTest {

    private final CreatorYoutubeChannelRepository channels = mock(CreatorYoutubeChannelRepository.class);
    private final YoutubeChannelOwnershipClient ownershipClient = mock(YoutubeChannelOwnershipClient.class);
    private final SubscriptionVerificationChannelIdentityService service =
            new SubscriptionVerificationChannelIdentityService(channels, ownershipClient);

    @Test
    void 현재_핸들이_다른_채널로_재할당되면_승인하지_않는다() {
        CreatorYoutubeChannel channel = verifiedChannel();
        given(channels.findById(42L)).willReturn(Optional.of(channel));
        given(ownershipClient.currentChannelId("@channel")).willReturn("UC0000000000000000000000");

        assertThat(service.matchesCurrentChannel(42L, "@channel")).isFalse();
    }

    @Test
    void 현재_핸들이_검증된_채널을_가리키면_승인할_수_있다() {
        CreatorYoutubeChannel channel = verifiedChannel();
        given(channels.findById(42L)).willReturn(Optional.of(channel));
        given(ownershipClient.currentChannelId("@channel"))
                .willReturn("UCabcdefghijklmnopqrstuv");

        assertThat(service.matchesCurrentChannel(42L, "@channel")).isTrue();
    }

    @Test
    void 배포_전_접수된_미검증_채널의_기존_요청은_기존_흐름으로_마무리한다() {
        given(channels.findById(42L)).willReturn(Optional.of(
                new CreatorYoutubeChannel(42L, "채널", "@channel", Instant.parse("2026-11-04T00:00:00Z"))));

        assertThat(service.matchesCurrentChannel(42L, "@channel")).isTrue();
        then(ownershipClient).should(never()).currentChannelId("@channel");
    }

    private CreatorYoutubeChannel verifiedChannel() {
        Instant now = Instant.parse("2026-11-04T00:00:00Z");
        CreatorYoutubeChannel channel = new CreatorYoutubeChannel(42L, "채널", "@channel", now);
        channel.verify("UCabcdefghijklmnopqrstuv", "채널", "@channel", now);
        return channel;
    }
}
