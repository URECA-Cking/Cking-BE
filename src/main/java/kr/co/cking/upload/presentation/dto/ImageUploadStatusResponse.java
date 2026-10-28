package kr.co.cking.upload.presentation.dto;

import kr.co.cking.upload.domain.ImageUpload;
import kr.co.cking.upload.domain.ImageUploadStatus;

/** READY일 때만 imageKey가 있다. 그 키를 기존 프로필·게시글·배너 API에 그대로 쓴다. */
public record ImageUploadStatusResponse(String uploadId, ImageUploadStatus status, String imageKey, String failureReason) {

    public static ImageUploadStatusResponse of(ImageUpload upload) {
        return new ImageUploadStatusResponse(
                upload.getUploadId(),
                upload.getStatus(),
                upload.getStatus() == ImageUploadStatus.READY ? upload.getObjectKey() : null,
                upload.getFailureReason());
    }
}
