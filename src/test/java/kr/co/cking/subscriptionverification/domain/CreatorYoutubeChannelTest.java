package kr.co.cking.subscriptionverification.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreatorYoutubeChannelTest {

    private static final Instant NOW = Instant.parse("2026-09-28T00:00:00Z");

    @Test
    void 채널명과_handle을_정규화하고_URL을_생성한다() {
        CreatorYoutubeChannel channel = new CreatorYoutubeChannel(
                1L, "  예상치 못한 필름  ", "@@UnexpectedFilm", NOW);

        assertThat(channel.getChannelName()).isEqualTo("예상치 못한 필름");
        assertThat(channel.getChannelHandle()).isEqualTo("@unexpectedfilm");
        assertThat(channel.getChannelUrl()).isEqualTo("https://www.youtube.com/@unexpectedfilm");
    }

    @Test
    void 한글_handle도_Locale_ROOT_소문자_정규화를_거쳐_허용한다() {
        assertThat(CreatorYoutubeChannel.normalizeHandle(" @크킹_Official "))
                .isEqualTo("@크킹_official");
    }

    @Test
    void 공백과_URL_구분자가_포함된_handle은_거부한다() {
        assertThatThrownBy(() -> CreatorYoutubeChannel.normalizeHandle("@bad handle"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CreatorYoutubeChannel.normalizeHandle("youtube.com/@handle"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 정규화한_설정이_같으면_멱등_재적용으로_판단한다() {
        CreatorYoutubeChannel channel = new CreatorYoutubeChannel(
                1L, "채널", "@sample", NOW);

        assertThat(channel.hasSameConfiguration(" 채널 ", "@@SAMPLE")).isTrue();
    }
}
