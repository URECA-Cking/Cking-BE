package kr.co.cking.common.storage;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public abstract class ObjectStorageContractTest {

    protected static final Duration MAX_PRESIGNED_TTL = Duration.ofMinutes(10);
    private static final String CONTENT_TYPE = "image/jpeg";

    protected abstract ObjectStorage storage();

    @Test
    void 저장한_내용을_그대로_읽는다() {
        byte[] content = bytes("image");

        storage().put("test/read.jpg", content, CONTENT_TYPE);

        assertThat(storage().get("test/read.jpg")).isEqualTo(content);
    }

    @Test
    void 저장_결과에_키_크기_eTag를_담는다() {
        PutResult result = storage().put("test/result.jpg", bytes("hello"), CONTENT_TYPE);

        assertThat(result.objectKey()).isEqualTo("test/result.jpg");
        assertThat(result.size()).isEqualTo(5);
        assertThat(result.eTag()).isEqualTo("5d41402abc4b2a76b9719d911017c592");
    }

    @Test
    void 같은_키로_다시_저장하면_예외를_던지고_기존_내용을_유지한다() {
        storage().put("test/duplicate.jpg", bytes("first"), CONTENT_TYPE);

        assertThatThrownBy(() -> storage().put("test/duplicate.jpg", bytes("second"), CONTENT_TYPE))
                .isInstanceOf(ObjectAlreadyExistsException.class);
        assertThat(storage().get("test/duplicate.jpg")).isEqualTo(bytes("first"));
    }

    @Test
    void 없는_키를_읽으면_ObjectNotFoundException을_던진다() {
        assertThatThrownBy(() -> storage().get("test/missing.jpg"))
                .isInstanceOf(ObjectNotFoundException.class);
    }

    @Test
    void 지운_키를_읽으면_ObjectNotFoundException을_던진다() {
        storage().put("test/deleted.jpg", bytes("image"), CONTENT_TYPE);

        storage().delete("test/deleted.jpg");

        assertThatThrownBy(() -> storage().get("test/deleted.jpg"))
                .isInstanceOf(ObjectNotFoundException.class);
    }

    @Test
    void 없는_키를_지워도_예외를_던지지_않는다() {
        assertThatCode(() -> storage().delete("test/never-stored.jpg"))
                .doesNotThrowAnyException();
    }

    @Test
    void 저장한_뒤_원본_배열을_바꿔도_저장된_내용은_그대로다() {
        byte[] content = bytes("image");
        storage().put("test/source-copy.jpg", content, CONTENT_TYPE);

        content[0] = 'X';

        assertThat(storage().get("test/source-copy.jpg")).isEqualTo(bytes("image"));
    }

    @Test
    void 읽은_배열을_바꿔도_저장된_내용은_그대로다() {
        storage().put("test/read-copy.jpg", bytes("image"), CONTENT_TYPE);

        storage().get("test/read-copy.jpg")[0] = 'X';

        assertThat(storage().get("test/read-copy.jpg")).isEqualTo(bytes("image"));
    }

    @Test
    void 빈_키는_거절한다() {
        assertThatThrownBy(() -> storage().put(" ", bytes("image"), CONTENT_TYPE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 유효_시간이_0_이하면_임시_주소를_거절한다() {
        assertThatThrownBy(() -> storage().presignedGetUrl("test/url.jpg", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage().presignedGetUrl("test/url.jpg", Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 유효_시간이_상한을_넘으면_임시_주소를_거절한다() {
        assertThatThrownBy(() -> storage().presignedGetUrl("test/url.jpg", MAX_PRESIGNED_TTL.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 유효_시간이_상한_이내면_임시_주소를_만든다() {
        storage().put("test/url.jpg", bytes("image"), CONTENT_TYPE);

        assertThat(storage().presignedGetUrl("test/url.jpg", MAX_PRESIGNED_TTL)).isNotNull();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
