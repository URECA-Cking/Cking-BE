package kr.co.cking.common.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;

/** 공유 의존성이 끊겨도 liveness(/livez)는 정상이고 배포 확인(/readyz)만 비정상인지 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
class HealthProbeSeparationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void DB와_Redis가_끊기면_readyz만_비정상이다() throws Exception {
        mockMvc.perform(get("/readyz"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"));
        mockMvc.perform(get("/livez"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @TestConfiguration
    static class DownDependencies {
        @Bean("redisHealthContributor")
        HealthIndicator redisHealthContributor() {
            return () -> Health.down().withDetail("reason", "test").build();
        }

        @Bean("dbHealthContributor")
        HealthIndicator dbHealthContributor() {
            return () -> Health.down().withDetail("reason", "test").build();
        }
    }
}
