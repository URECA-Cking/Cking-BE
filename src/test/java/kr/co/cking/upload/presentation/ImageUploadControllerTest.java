package kr.co.cking.upload.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.common.storage.PresignedUpload;
import kr.co.cking.upload.application.ImageUploadService;
import kr.co.cking.upload.domain.ImageUpload;
import kr.co.cking.upload.domain.ImageUploadPurpose;
import kr.co.cking.upload.domain.ImageUploadStatus;
import kr.co.cking.upload.domain.UploadErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ImageUploadController.class)
@TestPropertySource(properties = "cking.image-pipeline.results-queue-url=https://sqs.test/results")
class ImageUploadControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");
    private static final String KEY = "post-images/1/a.jpg";
    private static final String CREATE_BODY = """
            {"purpose": "POST_IMAGE", "contentType": "image/jpeg", "size": 1000}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ImageUploadService service;

    @Test
    @WithMockJwt(memberId = "7")
    void 예약은_201과_사전_서명_PUT_주소_헤더_만료_시각을_반환한다() throws Exception {
        PresignedUpload presigned = new PresignedUpload(URI.create("https://s3.test/raw/" + KEY),
                Map.of("content-type", "image/jpeg", "if-none-match", "*"));
        given(service.create(7L, ImageUploadPurpose.POST_IMAGE, "image/jpeg", 1000))
                .willReturn(new ImageUploadService.CreatedUpload("u-1", presigned, NOW.plusSeconds(300)));

        mockMvc.perform(post("/api/uploads").contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.uploadId").value("u-1"))
                .andExpect(jsonPath("$.data.uploadUrl").value("https://s3.test/raw/" + KEY))
                .andExpect(jsonPath("$.data.headers.content-type").value("image/jpeg"))
                .andExpect(jsonPath("$.data.headers.if-none-match").value("*"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-10-29T00:05:00Z"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 용도가_없으면_400이고_예약하지_않는다() throws Exception {
        assertValidationFailed("""
                {"contentType": "image/jpeg", "size": 1000}
                """);
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 알_수_없는_용도면_400이고_예약하지_않는다() throws Exception {
        assertValidationFailed("""
                {"purpose": "AVATAR", "contentType": "image/jpeg", "size": 1000}
                """);
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 형식이_비어_있으면_400이고_예약하지_않는다() throws Exception {
        assertValidationFailed("""
                {"purpose": "POST_IMAGE", "contentType": " ", "size": 1000}
                """);
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 크기가_없거나_0이면_서비스에_닿기_전에_400_VALIDATION_FAILED다() throws Exception {
        assertValidationFailed("""
                {"purpose": "POST_IMAGE", "contentType": "image/jpeg"}
                """);
        assertValidationFailed("""
                {"purpose": "POST_IMAGE", "contentType": "image/jpeg", "size": 0}
                """);
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 서비스가_형식이나_크기를_거절하면_400_INVALID_UPLOAD_REQUEST다() throws Exception {
        given(service.create(7L, ImageUploadPurpose.POST_IMAGE, "image/gif", 1000))
                .willThrow(new BusinessException(UploadErrorCode.INVALID_UPLOAD_REQUEST));

        mockMvc.perform(post("/api/uploads").contentType(MediaType.APPLICATION_JSON).content("""
                        {"purpose": "POST_IMAGE", "contentType": "image/gif", "size": 1000}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_UPLOAD_REQUEST"));
    }

    @Test
    void 예약은_JWT가_없으면_401이고_예약하지_않는다() throws Exception {
        mockMvc.perform(post("/api/uploads").contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        then(service).should(never()).create(any(), any(), any(), anyLong());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 상태_조회는_READY일_때만_imageKey를_준다() throws Exception {
        given(service.get(7L, "u-1")).willReturn(upload(ImageUploadStatus.READY, null));

        mockMvc.perform(get("/api/uploads/u-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.uploadId").value("u-1"))
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.imageKey").value(KEY));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 상태_조회는_PENDING이면_imageKey와_failureReason이_비어_있다() throws Exception {
        given(service.get(7L, "u-1")).willReturn(upload(ImageUploadStatus.PENDING, null));

        mockMvc.perform(get("/api/uploads/u-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.imageKey").doesNotExist())
                .andExpect(jsonPath("$.data.failureReason").doesNotExist());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 상태_조회는_FAILED면_사유를_주고_imageKey는_주지_않는다() throws Exception {
        given(service.get(7L, "u-1")).willReturn(upload(ImageUploadStatus.FAILED, "dimensions"));

        mockMvc.perform(get("/api/uploads/u-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failureReason").value("dimensions"))
                .andExpect(jsonPath("$.data.imageKey").doesNotExist());
    }

    @Test
    @WithMockJwt(memberId = "8")
    void 다른_회원의_업로드나_없는_업로드는_404_UPLOAD_NOT_FOUND다() throws Exception {
        given(service.get(8L, "u-1")).willThrow(new BusinessException(UploadErrorCode.UPLOAD_NOT_FOUND));

        mockMvc.perform(get("/api/uploads/u-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UPLOAD_NOT_FOUND"));
    }

    @Test
    void 상태_조회는_JWT가_없으면_401이다() throws Exception {
        mockMvc.perform(get("/api/uploads/u-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        then(service).should(never()).get(any(), anyString());
    }

    private void assertValidationFailed(String body) throws Exception {
        mockMvc.perform(post("/api/uploads").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        then(service).should(never()).create(any(), any(), any(), anyLong());
    }

    private static ImageUpload upload(ImageUploadStatus status, String failureReason) {
        ImageUpload upload = ImageUpload.pending(7L, ImageUploadPurpose.POST_IMAGE, KEY, NOW);
        ReflectionTestUtils.setField(upload, "uploadId", "u-1");
        ReflectionTestUtils.setField(upload, "status", status);
        ReflectionTestUtils.setField(upload, "failureReason", failureReason);
        return upload;
    }
}
