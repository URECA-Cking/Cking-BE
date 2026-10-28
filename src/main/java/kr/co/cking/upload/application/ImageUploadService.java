package kr.co.cking.upload.application;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.image.ImageNormalizer;
import kr.co.cking.common.storage.ObjectStorage;
import kr.co.cking.common.storage.PresignedUpload;
import kr.co.cking.upload.domain.ImageUpload;
import kr.co.cking.upload.domain.ImageUploadPurpose;
import kr.co.cking.upload.domain.ImageUploadStatus;
import kr.co.cking.upload.domain.UploadErrorCode;
import kr.co.cking.upload.repository.ImageUploadRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

/**
 * 이미지 업로드를 예약하고 사전 서명 PUT을 준 뒤, 정규화 결과를 반영한다(#780).
 *
 * <p>원본은 raw/&lt;object key&gt;에 올라오고 정규화 Lambda가 같은 키에 결과를 저장한다. 최소 크기는 서명에 넣은
 * 메타데이터로 Lambda에 전달한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnExpression("!'${cking.image-pipeline.results-queue-url:}'.isEmpty()")
public class ImageUploadService {

    static final String RAW_PREFIX = "raw/";
    static final Duration UPLOAD_URL_TTL = Duration.ofMinutes(5);
    private static final Set<String> CONTENT_TYPES = Set.of("image/jpeg", "image/png");

    private final ImageUploadRepository repository;
    private final ImageUploadTargets targets;
    private final ObjectStorage objectStorage;
    private final Clock clock;

    public CreatedUpload create(Long memberId, ImageUploadPurpose purpose, String contentType, long size) {
        if (purpose == null || !CONTENT_TYPES.contains(contentType)
                || size <= 0 || size > ImageNormalizer.MAX_SOURCE_BYTES) {
            throw new BusinessException(UploadErrorCode.INVALID_UPLOAD_REQUEST);
        }
        String key = targets.reserve(purpose, memberId);
        ImageUpload upload = repository.save(ImageUpload.pending(memberId, purpose, key, clock.instant()));
        PresignedUpload presigned = objectStorage.presignedPut(RAW_PREFIX + key, contentType, size,
                Map.of("min-width", String.valueOf(purpose.minWidth()), "min-height", String.valueOf(purpose.minHeight())),
                UPLOAD_URL_TTL);
        return new CreatedUpload(upload.getUploadId(), presigned, clock.instant().plus(UPLOAD_URL_TTL));
    }

    public ImageUpload get(Long memberId, String uploadId) {
        return repository.findById(uploadId)
                .filter(upload -> upload.getMemberId().equals(memberId))
                .orElseThrow(() -> new BusinessException(UploadErrorCode.UPLOAD_NOT_FOUND));
    }

    /** 결과 큐 메시지 하나를 반영한다. 같은 결과가 다시 와도 상태는 바뀌지 않는다. */
    public void complete(String key, ImageUploadStatus status, String reason) {
        ImageUpload upload = repository.findByObjectKey(key).orElse(null);
        if (upload == null) {
            log.warn("image upload result for unknown key: {}", key);
            return;
        }
        if (upload.getStatus() != ImageUploadStatus.PENDING) {
            return;
        }
        if (status == ImageUploadStatus.READY) {
            if (!targets.markUploaded(upload.getPurpose(), key)) {
                log.warn("image upload record is not UPLOADING: key={}", key);
            }
            repository.markReady(key, clock.instant());
        } else {
            repository.markFailed(key, reason == null ? "unknown" : reason, clock.instant());
        }
    }

    public record CreatedUpload(String uploadId, PresignedUpload presigned, java.time.Instant expiresAt) {
    }
}
