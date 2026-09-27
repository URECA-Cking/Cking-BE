package kr.co.cking.subscriptionverification.application.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SubscriptionImageProcessorTest {

    private final SubscriptionImageProcessor processor = new SubscriptionImageProcessor();

    @Test
    void jpeg를_정규화하고_원본과_정규화_해시를_반환한다() throws IOException {
        byte[] source = solidImage("jpeg", 600, 500, Color.BLUE);

        ProcessedSubscriptionImage result = processor.process(source);

        BufferedImage normalized = ImageIO.read(new ByteArrayInputStream(result.normalizedImageBytes()));
        assertThat(normalized.getWidth()).isEqualTo(600);
        assertThat(normalized.getHeight()).isEqualTo(500);
        assertThat(result.width()).isEqualTo(600);
        assertThat(result.height()).isEqualTo(500);
        assertThat(result.normalizationVersion()).isEqualTo("JPEG_V1");
        assertLowercaseSha256(result.sourceImageSha256());
        assertLowercaseSha256(result.normalizedImageSha256());
        assertThat(result.sourceImageSha256()).isEqualTo(ImageSha256.calculate(source));
        assertThat(result.normalizedImageSha256())
                .isEqualTo(ImageSha256.calculate(result.normalizedImageBytes()));
        assertThat(result.normalizedImageBytes()[0]).isEqualTo((byte) 0xFF);
        assertThat(result.normalizedImageBytes()[1]).isEqualTo((byte) 0xD8);
        assertThat(containsJpegMarker(result.normalizedImageBytes(), 0xC0)).isTrue();
        assertThat(containsJpegMarker(result.normalizedImageBytes(), 0xC2)).isFalse();
    }

    @Test
    void png를_실제_바이트_형식으로_판별해_처리한다() throws IOException {
        byte[] source = solidImage("png", 520, 480, Color.GREEN);

        ProcessedSubscriptionImage result = processor.process(source);

        assertThat(ImageIO.read(new ByteArrayInputStream(result.normalizedImageBytes())))
                .isNotNull();
        assertThat(result.width()).isEqualTo(520);
        assertThat(result.height()).isEqualTo(480);
    }

    @Test
    void 투명_png는_흰색_배경의_rgb_jpeg로_정규화한다() throws IOException {
        BufferedImage transparent = new BufferedImage(600, 600, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = transparent.createGraphics();
        try {
            graphics.setColor(new Color(255, 0, 0, 255));
            graphics.fillRect(200, 200, 200, 200);
        } finally {
            graphics.dispose();
        }
        byte[] source = write(transparent, "png");

        ProcessedSubscriptionImage result = processor.process(source);
        BufferedImage normalized = ImageIO.read(new ByteArrayInputStream(result.normalizedImageBytes()));
        Color background = new Color(normalized.getRGB(10, 10));

        assertThat(normalized.getColorModel().hasAlpha()).isFalse();
        assertThat(background.getRed()).isGreaterThan(245);
        assertThat(background.getGreen()).isGreaterThan(245);
        assertThat(background.getBlue()).isGreaterThan(245);
    }

    @Test
    void exif_orientation을_반영한_뒤_크기를_반환한다() throws IOException {
        byte[] jpeg = solidImage("jpeg", 700, 500, Color.ORANGE);
        byte[] rotatedByMetadata = addExifOrientation(jpeg, 6);

        ProcessedSubscriptionImage result = processor.process(rotatedByMetadata);

        assertThat(result.width()).isEqualTo(500);
        assertThat(result.height()).isEqualTo(700);
    }

    @ParameterizedTest
    @CsvSource({
        "2,0,255,0,false",
        "3,255,255,0,false",
        "4,0,0,255,false",
        "5,255,0,0,true",
        "6,0,0,255,true",
        "7,255,255,0,true",
        "8,0,255,0,true"
    })
    void exif_orientation의_회전과_반전을_정확하게_적용한다(
            int orientation,
            int expectedRed,
            int expectedGreen,
            int expectedBlue,
            boolean swapsAxes)
            throws IOException {
        byte[] source = addExifOrientation(quadrantJpeg(700, 500), orientation);

        ProcessedSubscriptionImage result = processor.process(source);
        BufferedImage normalized = ImageIO.read(new ByteArrayInputStream(result.normalizedImageBytes()));
        Color topLeft = new Color(normalized.getRGB(50, 50));

        assertThat(result.width()).isEqualTo(swapsAxes ? 500 : 700);
        assertThat(result.height()).isEqualTo(swapsAxes ? 700 : 500);
        assertThat(topLeft.getRed()).isCloseTo(expectedRed, within(10));
        assertThat(topLeft.getGreen()).isCloseTo(expectedGreen, within(10));
        assertThat(topLeft.getBlue()).isCloseTo(expectedBlue, within(10));
    }

    @Test
    void 긴_변이_2048을_넘으면_비율을_유지해_축소한다() throws IOException {
        byte[] source = solidImage("jpeg", 3000, 600, Color.MAGENTA);

        ProcessedSubscriptionImage result = processor.process(source);

        assertThat(result.width()).isEqualTo(2048);
        assertThat(result.height()).isEqualTo(410);
        assertThat((double) result.width() / result.height()).isCloseTo(5.0, within(0.01));
    }

    @Test
    void 작은_이미지는_업스케일하지_않는다() throws IOException {
        byte[] source = solidImage("png", 600, 500, Color.CYAN);

        ProcessedSubscriptionImage result = processor.process(source);

        assertThat(result.width()).isEqualTo(600);
        assertThat(result.height()).isEqualTo(500);
    }

    @Test
    void 크기_제한을_초과한_바이트는_디코딩_전에_거부한다() {
        byte[] source = new byte[SubscriptionImageProcessor.MAX_SOURCE_BYTES + 1];

        assertInvalidImage(() -> processor.process(source));
    }

    @Test
    void null과_빈_이미지는_거부한다() {
        assertInvalidImage(() -> processor.process(null));
        assertInvalidImage(() -> processor.process(new byte[0]));
    }

    @Test
    void 최소_해상도보다_작으면_거부한다() throws IOException {
        byte[] source = solidImage("png", 479, 480, Color.BLACK);

        assertInvalidImage(() -> processor.process(source));
    }

    @Test
    void 최대_픽셀을_초과하면_전체_픽셀을_읽기_전에_거부한다() throws IOException {
        byte[] headerOnlyPng = pngWithDimensions(5000, 4001);

        assertInvalidImage(() -> processor.process(headerOnlyPng));
    }

    @Test
    void 손상된_이미지와_지원하지_않는_형식은_거부한다() throws IOException {
        byte[] corruptJpeg = HexFormat.of().parseHex("ffd8ffe000104a4649460001");
        byte[] gif = solidImage("gif", 600, 600, Color.YELLOW);

        assertInvalidImage(() -> processor.process(corruptJpeg));
        assertInvalidImage(() -> processor.process(gif));
    }

    @Test
    void 정규화된_jpeg에는_exif_메타데이터가_남지_않는다() throws Exception {
        byte[] source = addExifOrientation(solidImage("jpeg", 700, 500, Color.PINK), 1);

        ProcessedSubscriptionImage result = processor.process(source);

        assertThat(
                        ImageMetadataReader.readMetadata(
                                        new ByteArrayInputStream(result.normalizedImageBytes()))
                                .getFirstDirectoryOfType(ExifIFD0Directory.class))
                .isNull();
    }

    @Test
    void 같은_입력은_항상_같은_정규화_바이트와_해시를_만든다() throws IOException {
        byte[] source = patternedPng(640, 520);

        ProcessedSubscriptionImage first = processor.process(source);
        ProcessedSubscriptionImage second = processor.process(source);

        assertThat(second.normalizedImageBytes()).isEqualTo(first.normalizedImageBytes());
        assertThat(second.normalizedImageSha256()).isEqualTo(first.normalizedImageSha256());
    }

    @Test
    void 픽셀이_같고_메타데이터만_다르면_정규화_해시는_같다() throws IOException {
        byte[] plain = solidImage("jpeg", 700, 500, Color.GRAY);
        byte[] withMetadata = addExifOrientation(plain, 1);

        ProcessedSubscriptionImage first = processor.process(plain);
        ProcessedSubscriptionImage second = processor.process(withMetadata);

        assertThat(second.sourceImageSha256()).isNotEqualTo(first.sourceImageSha256());
        assertThat(second.normalizedImageSha256()).isEqualTo(first.normalizedImageSha256());
    }

    @Test
    void 픽셀_내용이_달라지면_정규화_해시도_달라진다() throws IOException {
        ProcessedSubscriptionImage blue =
                processor.process(solidImage("png", 600, 600, Color.BLUE));
        ProcessedSubscriptionImage red =
                processor.process(solidImage("png", 600, 600, Color.RED));

        assertThat(red.normalizedImageSha256()).isNotEqualTo(blue.normalizedImageSha256());
    }

    @Test
    void sha256은_고정_입력에_대해_소문자_64자리_값을_반환한다() {
        assertThat(ImageSha256.calculate("abc".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void 결과의_이미지_바이트는_외부에서_변경할_수_없다() throws IOException {
        ProcessedSubscriptionImage result =
                processor.process(solidImage("png", 600, 600, Color.BLUE));
        byte[] firstRead = result.normalizedImageBytes();
        byte expected = firstRead[0];

        firstRead[0] = (byte) (expected + 1);

        assertThat(result.normalizedImageBytes()[0]).isEqualTo(expected);
    }

    @Test
    void 결과는_생성자에_전달된_이미지_바이트도_방어적으로_복사한다() {
        byte[] bytes = {1, 2, 3};
        ProcessedSubscriptionImage result =
                new ProcessedSubscriptionImage(bytes, "source", "normalized", "JPEG_V1", 1, 1);

        bytes[0] = 9;

        assertThat(result.normalizedImageBytes()).containsExactly(1, 2, 3);
    }

    private static <T extends Number> org.assertj.core.data.Offset<T> within(T value) {
        return org.assertj.core.data.Offset.offset(value);
    }

    private void assertInvalidImage(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.getErrorCode())
                                        .isEqualTo(
                                                SubscriptionVerificationErrorCode
                                                        .INVALID_VERIFICATION_IMAGE))
                .hasMessage("인증 이미지가 유효하지 않습니다.");
    }

    private void assertLowercaseSha256(String value) {
        assertThat(value).matches("[0-9a-f]{64}");
    }

    private byte[] solidImage(String format, int width, int height, Color color)
            throws IOException {
        BufferedImage image =
                new BufferedImage(
                        width,
                        height,
                        format.equals("jpeg") ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        return write(image, format);
    }

    private byte[] patternedPng(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, new Color(x % 256, y % 256, (x + y) % 256).getRGB());
            }
        }
        return write(image, "png");
    }

    private byte[] quadrantJpeg(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 0, width / 2, height / 2);
            graphics.setColor(Color.GREEN);
            graphics.fillRect(width / 2, 0, width - width / 2, height / 2);
            graphics.setColor(Color.BLUE);
            graphics.fillRect(0, height / 2, width / 2, height - height / 2);
            graphics.setColor(Color.YELLOW);
            graphics.fillRect(
                    width / 2,
                    height / 2,
                    width - width / 2,
                    height - height / 2);
        } finally {
            graphics.dispose();
        }
        return write(image, "jpeg");
    }

    private byte[] write(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, output)).isTrue();
        return output.toByteArray();
    }

    private boolean containsJpegMarker(byte[] jpeg, int marker) {
        for (int index = 0; index < jpeg.length - 1; index++) {
            if ((jpeg[index] & 0xFF) == 0xFF && (jpeg[index + 1] & 0xFF) == marker) {
                return true;
            }
        }
        return false;
    }

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
        data.writeByte(value & 0xFF);
        data.writeByte((value >>> 8) & 0xFF);
        data.writeByte((value >>> 16) & 0xFF);
        data.writeByte((value >>> 24) & 0xFF);
    }

    private byte[] pngWithDimensions(int width, int height) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(HexFormat.of().parseHex("89504e470d0a1a0a"));

        ByteArrayOutputStream ihdrData = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(ihdrData)) {
            data.writeInt(width);
            data.writeInt(height);
            data.writeByte(8);
            data.writeByte(2);
            data.writeByte(0);
            data.writeByte(0);
            data.writeByte(0);
        }
        writePngChunk(output, "IHDR", ihdrData.toByteArray());
        writePngChunk(output, "IEND", new byte[0]);
        return output.toByteArray();
    }

    private void writePngChunk(ByteArrayOutputStream output, String type, byte[] data)
            throws IOException {
        try (DataOutputStream stream = new DataOutputStream(output)) {
            byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
            stream.writeInt(data.length);
            stream.write(typeBytes);
            stream.write(data);
            CRC32 crc = new CRC32();
            crc.update(typeBytes);
            crc.update(data);
            stream.writeInt((int) crc.getValue());
        }
    }
}
