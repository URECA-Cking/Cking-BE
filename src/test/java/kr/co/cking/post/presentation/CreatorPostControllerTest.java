package kr.co.cking.post.presentation;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.image.ImageProcessingLimiter;
import kr.co.cking.common.image.ImageProcessingTestSupport;
import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.post.application.CreatorPostService;
import kr.co.cking.post.application.PostImageUploadService;
import kr.co.cking.post.application.dto.CreatorPostFields;
import kr.co.cking.post.application.dto.CreatorPostView;
import kr.co.cking.post.domain.PostErrorCode;
import kr.co.cking.post.domain.PostVisibility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CreatorPostController.class)
@Import(ImageProcessingTestSupport.Config.class)
class CreatorPostControllerTest {

    private static final String SAVE_BODY = """
            {"content": "첫 게시글", "visibility": "FOLLOWERS", "imageKeys": ["post-images/1/a.jpg"]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PostImageUploadService imageUploadService;

    @MockitoBean
    private CreatorPostService postService;

    @MockitoBean
    private MemberRepository memberRepository;

    @Autowired
    private ImageProcessingLimiter imageProcessingLimiter;

    @Test
    @WithMockJwt(memberId = "7")
    void 이미지_업로드는_201과_imageKey를_반환한다() throws Exception {
        MockMultipartFile image = new MockMultipartFile("image", "a.png", "image/png", new byte[] {1, 2});
        given(imageUploadService.upload(7L, new byte[] {1, 2})).willReturn("post-images/1/a.jpg");

        mockMvc.perform(multipart("/api/creator/posts/images").file(image))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.imageKey").value("post-images/1/a.jpg"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 이미지_처리_입장_자리가_없으면_503이고_업로드를_처리하지_않는다() throws Exception {
        MockMultipartFile image = new MockMultipartFile("image", "a.png", "image/png", new byte[] {1, 2});

        try (AutoCloseable ignored = ImageProcessingTestSupport.occupyAdmission(imageProcessingLimiter)) {
            mockMvc.perform(multipart("/api/creator/posts/images").file(image))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("IMAGE_PROCESSING_BUSY"));
        }
        then(imageUploadService).should(never()).upload(anyLong(), any());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 이미지_파트가_없으면_400이다() throws Exception {
        mockMvc.perform(multipart("/api/creator/posts/images"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 잘못된_이미지는_INVALID_POST_IMAGE다() throws Exception {
        MockMultipartFile image = new MockMultipartFile("image", "a.gif", "image/gif", new byte[] {1});
        given(imageUploadService.upload(eq(7L), any())).willThrow(new BusinessException(PostErrorCode.INVALID_POST_IMAGE));

        mockMvc.perform(multipart("/api/creator/posts/images").file(image))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_POST_IMAGE"));
    }

    @Test
    void 이미지_업로드는_JWT가_없으면_401이다() throws Exception {
        MockMultipartFile image = new MockMultipartFile("image", "a.png", "image/png", new byte[] {1});

        mockMvc.perform(multipart("/api/creator/posts/images").file(image))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 작성은_201과_작성자_기준_상세를_반환한다() throws Exception {
        given(postService.create(7L, new CreatorPostFields("첫 게시글", PostVisibility.FOLLOWERS, List.of("post-images/1/a.jpg"))))
                .willReturn(view());

        mockMvc.perform(post("/api/creator/posts").contentType(MediaType.APPLICATION_JSON).content(SAVE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.postId").value(100))
                .andExpect(jsonPath("$.data.visibility").value("FOLLOWERS"))
                .andExpect(jsonPath("$.data.locked").value(false))
                .andExpect(jsonPath("$.data.images[0].imageKey").value("post-images/1/a.jpg"))
                .andExpect(jsonPath("$.data.images[0].url").value("https://storage.test/a"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 이미지가_5장을_넘으면_400이다() throws Exception {
        String body = """
                {"content": "본문", "visibility": "PUBLIC", "imageKeys": ["a", "b", "c", "d", "e", "f"]}
                """;

        mockMvc.perform(post("/api/creator/posts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        then(postService).should(never()).create(anyLong(), any());
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 공개_범위가_없으면_400이다() throws Exception {
        mockMvc.perform(post("/api/creator/posts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"본문\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 사용할_수_없는_이미지가_있으면_POST_IMAGE_UNAVAILABLE이다() throws Exception {
        given(postService.create(eq(7L), any())).willThrow(new BusinessException(PostErrorCode.POST_IMAGE_UNAVAILABLE));

        mockMvc.perform(post("/api/creator/posts").contentType(MediaType.APPLICATION_JSON).content(SAVE_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("POST_IMAGE_UNAVAILABLE"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 수정은_200과_상세를_반환한다() throws Exception {
        given(postService.update(eq(7L), eq(100L), any())).willReturn(view());

        mockMvc.perform(patch("/api/creator/posts/100").contentType(MediaType.APPLICATION_JSON).content(SAVE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.postId").value(100));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 삭제는_204다() throws Exception {
        mockMvc.perform(delete("/api/creator/posts/100"))
                .andExpect(status().isNoContent());

        then(postService).should().delete(7L, 100L);
    }

    @Test
    void 작성은_JWT가_없으면_401이다() throws Exception {
        mockMvc.perform(post("/api/creator/posts").contentType(MediaType.APPLICATION_JSON).content(SAVE_BODY))
                .andExpect(status().isUnauthorized());
    }

    private CreatorPostView view() {
        Instant now = Instant.parse("2026-09-29T00:00:00Z");
        return new CreatorPostView(100L, 1L, PostVisibility.FOLLOWERS, false, "첫 게시글", 1,
                List.of(new CreatorPostView.Image("post-images/1/a.jpg", "https://storage.test/a")), now, now);
    }
}
