package kr.co.cking.common.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.co.cking.common.config.WebMvcConfig;
import kr.co.cking.common.exception.GlobalExceptionHandler;
import kr.co.cking.common.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@link CurrentMemberId}가 실제 MVC 요청에서 인증 실패를 올바르게 응답하는지 검증한다. */
@WebMvcTest(CurrentMemberIdWebMvcTest.CurrentMemberIdTestController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({
        CurrentMemberIdWebMvcTest.CurrentMemberIdTestController.class,
        WebMvcConfig.class,
        CurrentMemberIdArgumentResolver.class,
        GlobalExceptionHandler.class
})
class CurrentMemberIdWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    /** Bearer JWT 없이 CurrentMemberId를 요청하면 공통 UNAUTHORIZED 응답을 반환한다. */
    @Test
    void JWT_없이_CurrentMemberId를_요청하면_401을_반환한다() throws Exception {
        mockMvc.perform(get("/test/current-member"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"code\":\"UNAUTHORIZED\"}"));
    }

    /** 테스트용 CurrentMemberId endpoint다. */
    @RestController
    public static class CurrentMemberIdTestController {

        /** 인증된 Member ID를 그대로 반환한다. */
        @GetMapping("/test/current-member")
        ApiResponse<Long> currentMember(@CurrentMemberId Long memberId) {
            return ApiResponse.success(memberId);
        }
    }
}
