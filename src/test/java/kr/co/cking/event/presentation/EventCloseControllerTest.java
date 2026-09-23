package kr.co.cking.event.presentation;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.config.WebMvcConfig;
import kr.co.cking.common.security.CurrentMemberIdArgumentResolver;
import kr.co.cking.event.application.ManualEventCloseService;
import kr.co.cking.event.application.service.EventClosingService;
import kr.co.cking.event.domain.EventErrorCode;
import kr.co.cking.event.domain.EventStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EventCloseController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({WebMvcConfig.class, CurrentMemberIdArgumentResolver.class})
class EventCloseControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private ManualEventCloseService manualEventCloseService;

    @BeforeEach
    void authenticatedCreator() {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt("1"), java.util.List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void closeReturnsAcceptedClosingResponseEnvelope() throws Exception {
        given(manualEventCloseService.close(1L, 10L))
                .willReturn(new EventClosingService.ClosingResult(10L, EventStatus.CLOSING));

        mockMvc.perform(post("/api/events/{eventId}/close", 10L))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.eventId").value(10))
                .andExpect(jsonPath("$.data.status").value("CLOSING"));
    }

    @Test
    void closeRejectsMissingAccessToken() throws Exception {
        SecurityContextHolder.clearContext();

        mockMvc.perform(post("/api/events/{eventId}/close", 10L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void closeMapsInvalidStateToConflictResponse() throws Exception {
        given(manualEventCloseService.close(1L, 10L))
                .willThrow(new BusinessException(EventErrorCode.INVALID_STATE));

        mockMvc.perform(post("/api/events/{eventId}/close", 10L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    private Jwt jwt(String subject) {
        return Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(subject)
                .issuedAt(java.time.Instant.now())
                .expiresAt(java.time.Instant.now().plusSeconds(60))
                .build();
    }
}
