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
    private final String otherKey = "post-images/1/" + UUID.randomUUID() + ".jpg";

    @AfterEach
    void tearDown() {
        repository.findByObjectKey(key).ifPresent(repository::delete);
        repository.findByObjectKey(otherKey).ifPresent(repository::delete);
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

    /** #784: 원본이 올라오지 않은 PENDING만 기한 뒤 FAILED로 끝나고, 늦게 온 결과는 반영되지 않는다. */
    @Test
    void 기한이_지난_PENDING만_expired로_끝난다() {
        repository.save(ImageUpload.pending(1L, ImageUploadPurpose.POST_IMAGE, key, NOW.minusSeconds(7200)));
        repository.save(ImageUpload.pending(1L, ImageUploadPurpose.POST_IMAGE, otherKey, NOW));

        repository.expirePending(NOW.minusSeconds(3600), NOW);

        assertThat(repository.findByObjectKey(key)).get()
                .extracting(ImageUpload::getStatus, ImageUpload::getFailureReason)
                .containsExactly(ImageUploadStatus.FAILED, "expired");
        assertThat(repository.findByObjectKey(otherKey)).get()
                .extracting(ImageUpload::getStatus).isEqualTo(ImageUploadStatus.PENDING);
        assertThat(repository.markReady(key, NOW)).isZero();
    }

    @Test
    void 기준_시각_전에_만든_기록만_정한_수까지_지운다() {
        repository.save(ImageUpload.pending(1L, ImageUploadPurpose.POST_IMAGE, key, NOW.minusSeconds(90000)));
        repository.save(ImageUpload.pending(1L, ImageUploadPurpose.POST_IMAGE, otherKey, NOW));

        assertThat(repository.deleteCreatedBefore(NOW.minusSeconds(86400), 1000)).isEqualTo(1);

        assertThat(repository.findByObjectKey(key)).isEmpty();
        assertThat(repository.findByObjectKey(otherKey)).isPresent();
    }
}
