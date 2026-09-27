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

/** 매핑되지 않은 경로가 공통 404 응답으로 변환되는지 검증한다. */
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

    @RestController
    public static class PingController {

        @GetMapping("/test/ping")
        String ping() {
            return "pong";
        }
    }
}
