package kr.co.cking.upload.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import kr.co.cking.upload.domain.ImageUpload;
import kr.co.cking.upload.domain.ImageUploadPurpose;
import kr.co.cking.upload.domain.ImageUploadStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ImageUploadRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");

    @Autowired
    private ImageUploadRepository repository;

    private final String key = "post-images/1/" + UUID.randomUUID() + ".jpg";

    @AfterEach
    void tearDown() {
        repository.findByObjectKey(key).ifPresent(repository::delete);
    }

    /** #780: 결과 큐는 같은 메시지를 다시 줄 수 있어 PENDING일 때 한 번만 바뀐다. */
    @Test
    void 결과는_PENDING일_때_한_번만_반영된다() {
        repository.save(ImageUpload.pending(1L, ImageUploadPurpose.POST_IMAGE, key, NOW));

        assertThat(repository.markReady(key, NOW)).isEqualTo(1);
        assertThat(repository.markReady(key, NOW)).isZero();
        assertThat(repository.markFailed(key, "decode", NOW)).isZero();
        assertThat(repository.findByObjectKey(key)).get()
                .extracting(ImageUpload::getStatus).isEqualTo(ImageUploadStatus.READY);
    }
}
