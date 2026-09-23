package kr.co.cking.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Swagger Basic Auth와 actuator 공개 경로를 실제 SecurityFilterChain으로 검증한다. */
@SpringBootTest(properties = {
        "cking.docs.username=docs-user",
        "cking.docs.password=docs-password",
        "management.endpoints.web.exposure.include=health,info"
})
@AutoConfigureMockMvc
class DocsBasicAuthSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    /** Swagger 문서는 인증 정보가 없으면 Basic Auth 401 challenge를 반환하는지 검증한다. */
    @Test
    void Swagger_인증_정보가_없으면_401_challenge를_반환한다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isUnauthorized())
                .andExpect(result -> assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE))
                        .startsWith("Basic realm=\"Cking API Docs\""));
    }

    /** Swagger 문서는 올바른 Basic Auth를 전달하면 조회되는지 검증한다. */
    @Test
    void Swagger_올바른_인증_정보로_조회할_수_있다() throws Exception {
        mockMvc.perform(get("/v3/api-docs").with(httpBasic("docs-user", "docs-password")))
                .andExpect(status().isOk());
    }

    /** ALB 상태 확인에 필요한 health endpoint가 인증 없이 열려 있는지 검증한다. */
    @Test
    void actuator_health는_인증_없이_조회할_수_있다() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    /** 운영 정보 확인에 필요한 info endpoint가 인증 없이 열려 있는지 검증한다. */
    @Test
    void actuator_info는_인증_없이_조회할_수_있다() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk());
    }
}
