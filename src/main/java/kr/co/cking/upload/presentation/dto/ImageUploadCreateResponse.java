package kr.co.cking.upload.presentation.dto;

import java.time.Instant;
import java.util.Map;

/** 브라우저는 uploadUrl에 headers를 그대로 붙여 PUT하고, 끝나면 상태 조회로 imageKey를 받는다. */
public record ImageUploadCreateResponse(String uploadId, String uploadUrl, Map<String, String> headers, Instant expiresAt) {
}
