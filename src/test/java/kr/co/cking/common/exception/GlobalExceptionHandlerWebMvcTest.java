package kr.co.cking.common.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** 공통 예외가 공통 오류 응답으로 변환되는지 검증한다. */
@WebMvcTest(GlobalExceptionHandlerWebMvcTest.PingController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandlerWebMvcTest.PingController.class, GlobalExceptionHandler.class})
class GlobalExceptionHandlerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 매핑되지_않은_경로는_404와_RESOURCE_NOT_FOUND를_반환한다() throws Exception {
        mockMvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"code\":\"RESOURCE_NOT_FOUND\"}"));
    }

    @Test
    void 업로드_크기를_넘으면_413과_UPLOAD_TOO_LARGE를_반환한다() throws Exception {
        mockMvc.perform(get("/test/upload-too-large"))
                .andExpect(status().isContentTooLarge())
                .andExpect(content().json("{\"code\":\"UPLOAD_TOO_LARGE\"}"));
    }

    @RestController
    public static class PingController {

        @GetMapping("/test/ping")
        String ping() {
            return "pong";
        }

        @GetMapping("/test/upload-too-large")
        String uploadTooLarge() {
            throw new MaxUploadSizeExceededException(6 * 1024 * 1024);
        }
    }
}
