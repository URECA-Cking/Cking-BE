package kr.co.cking.subscriptionverification.application.vision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class VisionAnalysisContractTest {

    @Test
    void 분석_요청은_이미지를_방어적으로_복사하고_대상_채널을_정규화한다() {
        byte[] image = {1, 2, 3};

        VisionAnalysisRequest request =
                new VisionAnalysisRequest(image, " 예상치 못한 필름 ", "@@UnexpectedFilm");
        image[0] = 9;
        byte[] returned = request.normalizedJpegBytes();
        returned[1] = 9;

        assertThat(request.normalizedJpegBytes()).containsExactly(1, 2, 3);
        assertThat(request.targetChannelName()).isEqualTo("예상치 못한 필름");
        assertThat(request.targetChannelHandle()).isEqualTo("@unexpectedfilm");
    }

    @Test
    void 빈_이미지나_유효하지_않은_대상_채널은_분석_요청에서_거부한다() {
        assertThatThrownBy(() -> new VisionAnalysisRequest(new byte[0], "채널", "@channel"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VisionAnalysisRequest(new byte[] {1}, " ", "@channel"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VisionAnalysisRequest(new byte[] {1}, "채널", "bad handle"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 분석_결과는_관측_문자열을_trim하고_blank를_null로_정규화한다() {
        VisionAnalysisResult result = new VisionAnalysisResult(
                VisionPlatform.YOUTUBE,
                " 채널 ",
                "   ",
                VisionSubscriptionState.SUBSCRIBED,
                true,
                0.95);

        assertThat(result.observedChannelName()).isEqualTo("채널");
        assertThat(result.observedChannelHandle()).isNull();
    }

    @Test
    void confidence는_0과_1을_포함한_범위만_허용한다() {
        assertThat(result(0.0).confidence()).isZero();
        assertThat(result(1.0).confidence()).isEqualTo(1.0);
        assertThatThrownBy(() -> result(-0.01)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result(1.01)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> result(Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void Provider_기술_오류는_재시도_가능성을_명시한다() {
        VisionAnalysisException retryable = new VisionAnalysisException(
                VisionAnalysisFailureType.RETRYABLE, "timeout");
        VisionAnalysisException nonRetryable = new VisionAnalysisException(
                VisionAnalysisFailureType.NON_RETRYABLE, "bad request");

        assertThat(retryable.isRetryable()).isTrue();
        assertThat(nonRetryable.isRetryable()).isFalse();
    }

    private VisionAnalysisResult result(double confidence) {
        return new VisionAnalysisResult(
                VisionPlatform.YOUTUBE,
                "채널",
                "@channel",
                VisionSubscriptionState.SUBSCRIBED,
                true,
                confidence);
    }
}
