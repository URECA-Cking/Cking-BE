package kr.co.cking.interest.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** 실제 SecurityFilterChain에서 회원가입 화면용 분류 목록이 인증 없이 열려 있는지 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
class InterestApiSecurityIntegrationTest {

    @Autowired MockMvc mockMvc;

    @Test
    void 선택_가능한_관심_분야_목록은_인증_없이_조회할_수_있다() throws Exception {
        mockMvc.perform(get("/api/interests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.maxSelection").value(3));
    }
}
