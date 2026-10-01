package kr.co.cking.common.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageNormalizerTest {

    private static final ImagePolicy SMALL_MIN_POLICY = new ImagePolicy(200, 200, 2048);
    private static final ImagePolicy LARGE_MIN_POLICY = new ImagePolicy(480, 480, 2048);

    private final ImageNormalizer normalizer =
            new ImageNormalizer(new ImageProcessingLimiter(1, Duration.ofSeconds(5)));

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
    void 최소_해상도는_EXIF_회전을_반영한_최종_방향_기준으로_검증한다() throws IOException {
        byte[] rotatedToPortrait = addExifOrientation(solidImage("jpeg", 700, 500), 6);

        assertThatThrownBy(() -> normalizer.normalize(rotatedToPortrait, new ImagePolicy(600, 400, 2048)))
                .isInstanceOf(InvalidImageException.class);

        NormalizedImage result = normalizer.normalize(rotatedToPortrait, new ImagePolicy(400, 600, 2048));
        assertThat(result.width()).isEqualTo(500);
        assertThat(result.height()).isEqualTo(700);
    }

    @Test
    void 축을_바꾸지_않는_EXIF_회전은_원본_방향_기준으로_검증한다() throws IOException {
        byte[] upsideDown = addExifOrientation(solidImage("jpeg", 700, 500), 3);

        NormalizedImage result = normalizer.normalize(upsideDown, new ImagePolicy(600, 400, 2048));

        assertThat(result.width()).isEqualTo(700);
        assertThat(result.height()).isEqualTo(500);
        assertThatThrownBy(() -> normalizer.normalize(upsideDown, new ImagePolicy(400, 600, 2048)))
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

    /** JPEG SOI 바로 뒤에 Orientation 태그 하나만 가진 APP1(Exif) 세그먼트를 넣는다. */
    private byte[] addExifOrientation(byte[] jpeg, int orientation) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(payload)) {
            data.writeBytes("Exif");
            data.writeShort(0);
            data.writeBytes("II");
            writeLittleEndianShort(data, 42);
            writeLittleEndianInt(data, 8);
            writeLittleEndianShort(data, 1);
            writeLittleEndianShort(data, 0x0112);
            writeLittleEndianShort(data, 3);
            writeLittleEndianInt(data, 1);
            writeLittleEndianShort(data, orientation);
            writeLittleEndianShort(data, 0);
            writeLittleEndianInt(data, 0);
        }

        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(jpeg, 0, 2);
        result.write(0xFF);
        result.write(0xE1);
        int segmentLength = payload.size() + 2;
        result.write((segmentLength >>> 8) & 0xFF);
        result.write(segmentLength & 0xFF);
        payload.writeTo(result);
        result.write(jpeg, 2, jpeg.length - 2);
        return result.toByteArray();
    }

    private void writeLittleEndianShort(DataOutputStream data, int value) throws IOException {
        data.writeByte(value & 0xFF);
        data.writeByte((value >>> 8) & 0xFF);
    }

    private void writeLittleEndianInt(DataOutputStream data, int value) throws IOException {
        writeLittleEndianShort(data, value & 0xFFFF);
        writeLittleEndianShort(data, (value >>> 16) & 0xFFFF);
    }
}
