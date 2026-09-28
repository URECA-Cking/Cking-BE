package kr.co.cking.subscriptionverification.application.image;

import com.drew.imaging.ImageProcessingException;
import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 구독 인증 이미지의 형식과 크기를 검증하고 결정적인 JPEG로 정규화한다.
 *
 * <p>외부 파일명과 Content-Type은 신뢰하지 않으며 실제 이미지 헤더를 기준으로 JPEG/PNG만 허용한다.
 */
@Component
@Slf4j
public class SubscriptionImageProcessor {

    public static final String NORMALIZATION_VERSION = "JPEG_V1";
    public static final int MAX_SOURCE_BYTES = 5 * 1024 * 1024;
    public static final int MIN_WIDTH = 480;
    public static final int MIN_HEIGHT = 480;
    public static final long MAX_PIXEL_COUNT = 20_000_000L;
    public static final int MAX_LONG_EDGE = 2048;

    private static final float JPEG_QUALITY = 0.90F;

    public ProcessedSubscriptionImage process(byte[] sourceBytes) {
        validateSourceSize(sourceBytes);
        byte[] stableSourceBytes = sourceBytes.clone();
        String sourceHash = ImageSha256.calculate(stableSourceBytes);

        DecodedImage decoded;
        int orientation;
        try {
            decoded = decodeAfterHeaderValidation(stableSourceBytes);
            orientation = readExifOrientation(stableSourceBytes);
        } catch (BusinessException e) {
            throw e;
        } catch (IOException | ImageProcessingException e) {
            throw invalidImage();
        }

        try {
            BufferedImage normalized = normalize(decoded.image(), orientation);
            byte[] normalizedBytes = encodeJpeg(normalized);

            return new ProcessedSubscriptionImage(
                    normalizedBytes,
                    sourceHash,
                    ImageSha256.calculate(normalizedBytes),
                    NORMALIZATION_VERSION,
                    normalized.getWidth(),
                    normalized.getHeight());
        } catch (Exception e) {
            log.error("구독 인증 이미지 정규화 중 서버 오류가 발생했습니다.", e);
            throw new BusinessException(CommonErrorCode.SYSTEM_ERROR);
        }
    }

    private void validateSourceSize(byte[] sourceBytes) {
        if (sourceBytes == null || sourceBytes.length == 0 || sourceBytes.length > MAX_SOURCE_BYTES) {
            throw invalidImage();
        }
    }

    private DecodedImage decodeAfterHeaderValidation(byte[] sourceBytes) throws IOException {
        try (ImageInputStream input =
                ImageIO.createImageInputStream(new ByteArrayInputStream(sourceBytes))) {
            if (input == null) {
                throw invalidImage();
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw invalidImage();
            }

            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toUpperCase(Locale.ROOT);
                if (!format.equals("JPEG") && !format.equals("JPG") && !format.equals("PNG")) {
                    throw invalidImage();
                }

                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                validateDimensions(width, height);

                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw invalidImage();
                }
                return new DecodedImage(image);
            } finally {
                reader.dispose();
            }
        }
    }

    private void validateDimensions(int width, int height) {
        if (width < MIN_WIDTH || height < MIN_HEIGHT) {
            throw invalidImage();
        }
        if ((long) width * (long) height > MAX_PIXEL_COUNT) {
            throw invalidImage();
        }
    }

    private int readExifOrientation(byte[] sourceBytes)
            throws ImageProcessingException, IOException {
        Metadata metadata =
                ImageMetadataReader.readMetadata(new ByteArrayInputStream(sourceBytes));
        ExifIFD0Directory directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        if (directory == null || !directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
            return 1;
        }
        Integer orientation = directory.getInteger(ExifIFD0Directory.TAG_ORIENTATION);
        if (orientation == null || orientation < 1 || orientation > 8) {
            return 1;
        }
        return orientation;
    }

    private BufferedImage normalize(BufferedImage source, int orientation) {
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        boolean swapsAxes = orientation >= 5 && orientation <= 8;
        int orientedWidth = swapsAxes ? sourceHeight : sourceWidth;
        int orientedHeight = swapsAxes ? sourceWidth : sourceHeight;
        int longestEdge = Math.max(orientedWidth, orientedHeight);
        double scale = longestEdge > MAX_LONG_EDGE ? (double) MAX_LONG_EDGE / longestEdge : 1.0D;
        int targetWidth = Math.max(1, (int) Math.round(orientedWidth * scale));
        int targetHeight = Math.max(1, (int) Math.round(orientedHeight * scale));

        BufferedImage result = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, targetWidth, targetHeight);
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(
                    RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(
                    RenderingHints.KEY_COLOR_RENDERING,
                    RenderingHints.VALUE_COLOR_RENDER_QUALITY);
            AffineTransform transform =
                    AffineTransform.getScaleInstance(
                            (double) targetWidth / orientedWidth,
                            (double) targetHeight / orientedHeight);
            transform.concatenate(orientationTransform(orientation, sourceWidth, sourceHeight));
            graphics.drawImage(source, transform, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private AffineTransform orientationTransform(
            int orientation, int sourceWidth, int sourceHeight) {
        return switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, sourceWidth, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, sourceWidth, sourceHeight);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, sourceHeight);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, sourceHeight, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, sourceHeight, sourceWidth);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, sourceWidth);
            default -> new AffineTransform();
        };
    }

    private byte[] encodeJpeg(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("JPEG writer is unavailable");
        }

        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
                ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            if (param.canWriteProgressive()) {
                param.setProgressiveMode(ImageWriteParam.MODE_DISABLED);
            }
            writer.write(null, new IIOImage(image, null, null), param);
            imageOutput.flush();
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private BusinessException invalidImage() {
        return new BusinessException(
                SubscriptionVerificationErrorCode.INVALID_VERIFICATION_IMAGE);
    }

    private record DecodedImage(BufferedImage image) {}
}
