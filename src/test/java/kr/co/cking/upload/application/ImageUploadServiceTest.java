package kr.co.cking.upload.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.common.storage.PresignedUpload;
import kr.co.cking.upload.domain.ImageUpload;
import kr.co.cking.upload.domain.ImageUploadPurpose;
import kr.co.cking.upload.domain.ImageUploadStatus;
import kr.co.cking.upload.domain.UploadErrorCode;
import kr.co.cking.upload.repository.ImageUploadRepository;
import org.junit.jupiter.api.Test;

class ImageUploadServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");
    private static final String KEY = "creator-space-profile-images/42/banner/a.jpg";

    private final ImageUploadRepository repository = mock(ImageUploadRepository.class);
    private final ImageUploadTargets targets = mock(ImageUploadTargets.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final ImageUploadService service =
            new ImageUploadService(repository, targets, storage, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void 예약은_용도별_권한_확인_뒤_raw_키에_최소_크기를_서명한_PUT을_준다() {
        given(targets.reserve(ImageUploadPurpose.CREATOR_SPACE_BANNER, 7L)).willReturn(KEY);
        given(repository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
        PresignedUpload presigned = new PresignedUpload(URI.create("https://s3/raw"), Map.of("content-type", "image/png"));
        given(storage.presignedPut(anyString(), anyString(), anyLong(), anyMap(), any())).willReturn(presigned);

        ImageUploadService.CreatedUpload created =
                service.create(7L, ImageUploadPurpose.CREATOR_SPACE_BANNER, "image/png", 1_000_000);

        then(storage).should().presignedPut("raw/" + KEY, "image/png", 1_000_000,
                Map.of("min-width", "600", "min-height", "200"), Duration.ofMinutes(5));
        assertThat(created.presigned()).isEqualTo(presigned);
        assertThat(created.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    void 형식이나_크기가_원본_한도를_벗어나면_예약하지_않는다() {
        assertThatThrownBy(() -> service.create(7L, ImageUploadPurpose.POST_IMAGE, "image/gif", 100))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(UploadErrorCode.INVALID_UPLOAD_REQUEST));
        assertThatThrownBy(() -> service.create(7L, ImageUploadPurpose.POST_IMAGE, "image/jpeg", 5 * 1024 * 1024 + 1))
                .isInstanceOf(BusinessException.class);
        then(targets).should(never()).reserve(any(), anyLong());
    }

    @Test
    void 완료_결과는_용도별_업로드_기록을_UPLOADED로_바꾸고_업로드를_READY로_바꾼다() {
        given(repository.findByObjectKey(KEY)).willReturn(Optional.of(pending()));
        given(targets.markUploaded(ImageUploadPurpose.CREATOR_SPACE_BANNER, KEY)).willReturn(true);

        service.complete(KEY, ImageUploadStatus.READY, null);

        then(targets).should().markUploaded(ImageUploadPurpose.CREATOR_SPACE_BANNER, KEY);
        then(repository).should().markReady(KEY, NOW);
    }

    @Test
    void 같은_결과가_다시_오면_이미_끝난_업로드는_바꾸지_않는다() {
        ImageUpload done = pending();
        org.springframework.test.util.ReflectionTestUtils.setField(done, "status", ImageUploadStatus.READY);
        given(repository.findByObjectKey(KEY)).willReturn(Optional.of(done));

        service.complete(KEY, ImageUploadStatus.READY, null);

        then(targets).should(never()).markUploaded(any(), anyString());
        then(repository).should(never()).markReady(anyString(), any());
    }

    @Test
    void 실패_결과는_사유를_남기고_용도별_기록은_UPLOADING으로_두어_정리_작업이_회수한다() {
        given(repository.findByObjectKey(KEY)).willReturn(Optional.of(pending()));

        service.complete(KEY, ImageUploadStatus.FAILED, "dimensions");

        then(repository).should().markFailed(KEY, "dimensions", NOW);
        then(targets).should(never()).markUploaded(any(), anyString());
    }

    @Test
    void 다른_회원의_업로드는_없는_것으로_본다() {
        ImageUpload upload = pending();
        given(repository.findById(upload.getUploadId())).willReturn(Optional.of(upload));

        assertThatThrownBy(() -> service.get(8L, upload.getUploadId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(UploadErrorCode.UPLOAD_NOT_FOUND));
        assertThat(service.get(7L, upload.getUploadId())).isSameAs(upload);
    }

    private static ImageUpload pending() {
        return ImageUpload.pending(7L, ImageUploadPurpose.CREATOR_SPACE_BANNER, KEY, NOW);
    }
}
