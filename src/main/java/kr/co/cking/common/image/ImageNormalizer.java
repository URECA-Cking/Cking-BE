package kr.co.cking.common.image;

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
import java.util.Objects;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import lombok.extern.slf4j.Slf4j;

/**
 * 업로드 이미지의 형식과 크기를 검증하고 결정적인 JPEG로 정규화한다.
 *
 * <p>외부 파일명과 Content-Type은 신뢰하지 않으며 실제 이미지 헤더를 기준으로 JPEG/PNG만 허용한다. 최소
 * 해상도와 긴 변 최대값은 {@link ImagePolicy}로 받는다. 정규화 결과 hash를 중복 판별에 쓰는 도메인이 있으므로
 * 인코딩 설정을 바꾸면 {@link #NORMALIZATION_VERSION}도 올려야 한다.
 */
@Slf4j
public class ImageNormalizer {

    public static final String NORMALIZATION_VERSION = "JPEG_V1";
    public static final int MAX_SOURCE_BYTES = 5 * 1024 * 1024;
    public static final long MAX_PIXEL_COUNT = 20_000_000L;

    private static final float JPEG_QUALITY = 0.90F;

    private final ImageProcessingLimiter limiter;

    public ImageNormalizer(ImageProcessingLimiter limiter) {
        this.limiter = Objects.requireNonNull(limiter, "limiter");
    }

    /**
     * @throws InvalidImageException 형식·크기·해상도 위반이나 손상된 이미지
     * @throws BusinessException 입력은 유효하지만 정규화 중 서버 오류가 난 경우 ({@code SYSTEM_ERROR}),
     *         동시 실행 한도에 걸려 대기 시간을 넘긴 경우 ({@code IMAGE_PROCESSING_BUSY})
     */
    public NormalizedImage normalize(byte[] sourceBytes, ImagePolicy policy) {
        Objects.requireNonNull(policy, "policy");
        validateSourceSize(sourceBytes);
        return limiter.run(() -> normalizeWithinLimit(sourceBytes, policy));
    }

    private NormalizedImage normalizeWithinLimit(byte[] sourceBytes, ImagePolicy policy) {
        byte[] stableSourceBytes = sourceBytes.clone();
        String sourceHash = ImageSha256.calculate(stableSourceBytes);

        DecodedImage decoded;
        int orientation;
        try {
            orientation = readExifOrientation(stableSourceBytes);
            decoded = decodeAfterHeaderValidation(stableSourceBytes, orientation, policy);
        } catch (IOException | ImageProcessingException e) {
            throw new InvalidImageException();
        }

        try {
            BufferedImage normalized = normalize(decoded.image(), orientation, policy);
            byte[] normalizedBytes = encodeJpeg(normalized);

            return new NormalizedImage(
                    normalizedBytes,
                    sourceHash,
                    ImageSha256.calculate(normalizedBytes),
                    NORMALIZATION_VERSION,
                    normalized.getWidth(),
                    normalized.getHeight());
        } catch (Exception e) {
            log.error("이미지 정규화 중 서버 오류가 발생했습니다.", e);
            throw new BusinessException(CommonErrorCode.SYSTEM_ERROR);
        }
    }

    private void validateSourceSize(byte[] sourceBytes) {
        if (sourceBytes == null || sourceBytes.length == 0 || sourceBytes.length > MAX_SOURCE_BYTES) {
            throw new InvalidImageException();
        }
    }

    private DecodedImage decodeAfterHeaderValidation(
            byte[] sourceBytes, int orientation, ImagePolicy policy) throws IOException {
        try (ImageInputStream input =
                ImageIO.createImageInputStream(new ByteArrayInputStream(sourceBytes))) {
            if (input == null) {
                throw new InvalidImageException();
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new InvalidImageException();
            }

            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toUpperCase(Locale.ROOT);
                if (!format.equals("JPEG") && !format.equals("JPG") && !format.equals("PNG")) {
                    throw new InvalidImageException();
                }

                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                validateDimensions(width, height, orientation, policy);

                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new InvalidImageException();
                }
                return new DecodedImage(image);
            } finally {
                reader.dispose();
            }
        }
    }

    /** 최소 해상도는 EXIF Orientation을 반영한 최종 표시 방향 기준으로 검증한다. */
    private void validateDimensions(int width, int height, int orientation, ImagePolicy policy) {
        boolean swapsAxes = swapsAxes(orientation);
        int orientedWidth = swapsAxes ? height : width;
        int orientedHeight = swapsAxes ? width : height;
        if (orientedWidth < policy.minWidth() || orientedHeight < policy.minHeight()) {
            throw new InvalidImageException();
        }
        if ((long) width * (long) height > MAX_PIXEL_COUNT) {
            throw new InvalidImageException();
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

    private BufferedImage normalize(BufferedImage source, int orientation, ImagePolicy policy) {
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        boolean swapsAxes = swapsAxes(orientation);
        int orientedWidth = swapsAxes ? sourceHeight : sourceWidth;
        int orientedHeight = swapsAxes ? sourceWidth : sourceHeight;
        int longestEdge = Math.max(orientedWidth, orientedHeight);
        int maxLongEdge = policy.maxLongEdge();
        double scale = longestEdge > maxLongEdge ? (double) maxLongEdge / longestEdge : 1.0D;
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

    private boolean swapsAxes(int orientation) {
        return orientation >= 5 && orientation <= 8;
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

    private record DecodedImage(BufferedImage image) {}
}
