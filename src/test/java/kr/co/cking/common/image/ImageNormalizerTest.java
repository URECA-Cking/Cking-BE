package kr.co.cking.common.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageNormalizerTest {

    private static final ImagePolicy SMALL_MIN_POLICY = new ImagePolicy(200, 200, 2048);
    private static final ImagePolicy LARGE_MIN_POLICY = new ImagePolicy(480, 480, 2048);

    private final ImageNormalizer normalizer = new ImageNormalizer();

    @Test
    void 최소_해상도는_정책마다_다르게_적용된다() throws IOException {
        byte[] source = solidImage("png", 300, 300);

        NormalizedImage result = normalizer.normalize(source, SMALL_MIN_POLICY);

        assertThat(result.width()).isEqualTo(300);
        assertThat(result.height()).isEqualTo(300);
        assertThatThrownBy(() -> normalizer.normalize(source, LARGE_MIN_POLICY))
                .isInstanceOf(InvalidImageException.class);
    }

    @Test
    void 정책의_최소_해상도_경계값은_허용하고_1픽셀_작으면_거부한다() throws IOException {
        assertThat(normalizer.normalize(solidImage("jpeg", 200, 200), SMALL_MIN_POLICY).width())
                .isEqualTo(200);
        assertThatThrownBy(() -> normalizer.normalize(solidImage("jpeg", 200, 199), SMALL_MIN_POLICY))
                .isInstanceOf(InvalidImageException.class);
    }

    @Test
    void 긴_변은_정책의_최대값으로_비율을_유지해_축소한다() throws IOException {
        byte[] source = solidImage("jpeg", 1600, 400);

        NormalizedImage result = normalizer.normalize(source, new ImagePolicy(200, 200, 1080));

        assertThat(result.width()).isEqualTo(1080);
        assertThat(result.height()).isEqualTo(270);
    }

    @Test
    void 정규화_결과는_정책과_무관한_공통_버전을_가진다() throws IOException {
        NormalizedImage result = normalizer.normalize(solidImage("png", 300, 300), SMALL_MIN_POLICY);

        assertThat(result.normalizationVersion()).isEqualTo(ImageNormalizer.NORMALIZATION_VERSION);
        assertThat(result.normalizedImageSha256())
                .isEqualTo(ImageSha256.calculate(result.normalizedImageBytes()));
    }

    @Test
    void 공통_규칙_위반은_정책과_무관하게_공통_예외로_거부한다() {
        assertThatThrownBy(() -> normalizer.normalize(null, SMALL_MIN_POLICY))
                .isInstanceOf(InvalidImageException.class);
        assertThatThrownBy(() -> normalizer.normalize(
                        new byte[ImageNormalizer.MAX_SOURCE_BYTES + 1], SMALL_MIN_POLICY))
                .isInstanceOf(InvalidImageException.class);
        assertThatThrownBy(() -> normalizer.normalize("not-image".getBytes(), SMALL_MIN_POLICY))
                .isInstanceOf(InvalidImageException.class);
    }

    @Test
    void 정책은_필수다() throws IOException {
        byte[] source = solidImage("png", 300, 300);

        assertThatThrownBy(() -> normalizer.normalize(source, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void 잘못된_정책은_생성할_수_없다() {
        assertThatThrownBy(() -> new ImagePolicy(0, 200, 2048))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImagePolicy(480, 480, 479))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private byte[] solidImage(String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.BLUE);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, output)).isTrue();
        return output.toByteArray();
    }
}
