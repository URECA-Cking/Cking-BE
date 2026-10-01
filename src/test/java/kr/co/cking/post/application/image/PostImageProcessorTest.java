package kr.co.cking.post.application.image;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.image.ImageNormalizer;
import kr.co.cking.common.image.ImageProcessingLimiter;
import kr.co.cking.post.domain.PostErrorCode;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostImageProcessorTest {

    private final PostImageProcessor processor = new PostImageProcessor(
            new ImageNormalizer(new ImageProcessingLimiter(1, Duration.ofSeconds(5))));

    @Test
    void 게시글_정책의_최소_해상도_200x200을_허용한다() throws IOException {
        assertThat(processor.process(png(200, 200)).width()).isEqualTo(200);
    }

    @Test
    void 최소_해상도보다_작으면_게시글_이미지_오류다() throws IOException {
        byte[] source = png(199, 200);

        assertThatThrownBy(() -> processor.process(source))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(PostErrorCode.INVALID_POST_IMAGE));
    }

    @Test
    void 이미지가_아니면_게시글_이미지_오류다() {
        assertThatThrownBy(() -> processor.process("not-image".getBytes()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(PostErrorCode.INVALID_POST_IMAGE));
    }

    private byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }
}
