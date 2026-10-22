package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YoutubeVideoUrlTest {

    private static final String VIDEO_ID = "dQw4w9WgXcQ";

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtube.com/watch?v=dQw4w9WgXcQ",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://WWW.YouTube.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com:443/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com/watch?feature=share&v=dQw4w9WgXcQ&t=42s",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ#comments",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ/",
            "https://youtube.com/shorts/dQw4w9WgXcQ?feature=share",
            "https://youtu.be/dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ?si=abc&t=10"
    })
    void 허용한_형식에서_videoId만_꺼낸다(String url) {
        assertThat(YoutubeVideoUrl.videoIdOf(url)).isEqualTo(VIDEO_ID);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "http://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "ftp://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://evil.com/watch?v=dQw4w9WgXcQ",
            "https://youtube.com.evil.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com./watch?v=dQw4w9WgXcQ",
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://user@www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://user:pass@youtu.be/dQw4w9WgXcQ",
            "https://www.youtube.com:8443/watch?v=dQw4w9WgXcQ",
            "https://youtu.be:80/dQw4w9WgXcQ",
            "https://www.youtube.com/watch",
            "https://www.youtube.com/watch?list=abc",
            "https://www.youtube.com/watch?v=",
            "https://www.youtube.com/watch?v",
            "https://www.youtube.com/watch?v=dQw4w9WgXc",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQQ",
            "https://www.youtube.com/watch?v=dQw4w9WgX%63Q",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&v=dQw4w9WgXcQ",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&v=aaaaaaaaaaa",
            "https://www.youtube.com/watch/?v=dQw4w9WgXcQ",
            "https://www.youtube.com/embed/dQw4w9WgXcQ",
            "https://www.youtube.com/shorts/dQw4w9WgXc",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ/extra",
            "https://www.youtube.com/dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ/",
            "https://youtu.be/watch?v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgX%63Q",
            "https://youtu.be/",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ with space"
    })
    void 그_밖의_URL은_VALIDATION_FAILED다(String url) {
        assertThatThrownBy(() -> YoutubeVideoUrl.videoIdOf(url))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.VALIDATION_FAILED));
    }
}
