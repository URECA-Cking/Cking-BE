package kr.co.cking.subscriptionverification.application.image;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
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
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationErrorCode;
import org.springframework.stereotype.Component;

/**
 * 구독 인증 이미지의 형식과 크기를 검증하고 결정적인 JPEG로 정규화한다.
 *
 * <p>외부 파일명과 Content-Type은 신뢰하지 않으며 실제 이미지 헤더를 기준으로 JPEG/PNG만 허용한다.
 */
@Component
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

        try {
            DecodedImage decoded = decodeAfterHeaderValidation(stableSourceBytes);
            BufferedImage oriented =
                    applyOrientation(decoded.image(), readExifOrientation(stableSourceBytes));
            BufferedImage normalized = normalize(oriented);
            byte[] normalizedBytes = encodeJpeg(normalized);

            return new ProcessedSubscriptionImage(
                    normalizedBytes,
                    sourceHash,
                    ImageSha256.calculate(normalizedBytes),
                    NORMALIZATION_VERSION,
                    normalized.getWidth(),
                    normalized.getHeight());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw invalidImage();
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

    private int readExifOrientation(byte[] sourceBytes) throws Exception {
        Metadata metadata =
                ImageMetadataReader.readMetadata(new ByteArrayInputStream(sourceBytes));
        ExifIFD0Directory directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        if (directory == null || !directory.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
            return 1;
        }
        Integer orientation = directory.getInteger(ExifIFD0Directory.TAG_ORIENTATION);
        if (orientation == null || orientation < 1 || orientation > 8) {
            throw invalidImage();
        }
        return orientation;
    }

    private BufferedImage applyOrientation(BufferedImage source, int orientation) {
        if (orientation == 1) {
            return source;
        }

        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        boolean swapsAxes = orientation >= 5;
        BufferedImage result =
                new BufferedImage(
                        swapsAxes ? sourceHeight : sourceWidth,
                        swapsAxes ? sourceWidth : sourceHeight,
                        BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < sourceHeight; y++) {
            for (int x = 0; x < sourceWidth; x++) {
                int targetX;
                int targetY;
                switch (orientation) {
                    case 2 -> {
                        targetX = sourceWidth - 1 - x;
                        targetY = y;
                    }
                    case 3 -> {
                        targetX = sourceWidth - 1 - x;
                        targetY = sourceHeight - 1 - y;
                    }
                    case 4 -> {
                        targetX = x;
                        targetY = sourceHeight - 1 - y;
                    }
                    case 5 -> {
                        targetX = y;
                        targetY = x;
                    }
                    case 6 -> {
                        targetX = sourceHeight - 1 - y;
                        targetY = x;
                    }
                    case 7 -> {
                        targetX = sourceHeight - 1 - y;
                        targetY = sourceWidth - 1 - x;
                    }
                    case 8 -> {
                        targetX = y;
                        targetY = sourceWidth - 1 - x;
                    }
                    default -> throw invalidImage();
                }
                result.setRGB(targetX, targetY, source.getRGB(x, y));
            }
        }
        return result;
    }

    private BufferedImage normalize(BufferedImage source) {
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        int longestEdge = Math.max(sourceWidth, sourceHeight);
        double scale = longestEdge > MAX_LONG_EDGE ? (double) MAX_LONG_EDGE / longestEdge : 1.0D;
        int targetWidth = Math.max(1, (int) Math.round(sourceWidth * scale));
        int targetHeight = Math.max(1, (int) Math.round(sourceHeight * scale));

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
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return result;
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
