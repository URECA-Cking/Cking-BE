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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

    /** 인증된 Creator 요청을 재현할 JWT 주체를 SecurityContext에 설정한다. */
    @BeforeEach
    void authenticatedCreator() {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt("1"), java.util.List.of()));
    }

    /** 각 테스트 뒤 SecurityContext를 비워 다른 HTTP 계약 검증에 영향을 주지 않게 한다. */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** OPEN Event의 수동 마감 요청이 202와 CLOSING 상태를 응답하는지 검증한다. */
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

    /** 이미 마감 중이거나 완료된 Event도 202와 실제 현재 상태를 멱등하게 응답하는지 검증한다. */
    @ParameterizedTest
    @EnumSource(value = EventStatus.class, names = {"CLOSING", "CLOSED"})
    void closeReturnsAcceptedActualStatusForIdempotentRequest(EventStatus currentStatus) throws Exception {
        given(manualEventCloseService.close(1L, 10L))
                .willReturn(new EventClosingService.ClosingResult(10L, currentStatus));

        mockMvc.perform(post("/api/events/{eventId}/close", 10L))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.eventId").value(10))
                .andExpect(jsonPath("$.data.status").value(currentStatus.name()));
    }

    /** Access JWT가 없으면 수동 마감 요청을 401 공통 오류로 거부하는지 검증한다. */
    @Test
    void closeRejectsMissingAccessToken() throws Exception {
        SecurityContextHolder.clearContext();

        mockMvc.perform(post("/api/events/{eventId}/close", 10L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    /** 마감할 수 없는 Event 상태의 업무 오류가 409 응답으로 유지되는지 검증한다. */
    @Test
    void closeMapsInvalidStateToConflictResponse() throws Exception {
        given(manualEventCloseService.close(1L, 10L))
                .willThrow(new BusinessException(EventErrorCode.INVALID_STATE));

        mockMvc.perform(post("/api/events/{eventId}/close", 10L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    /** CurrentMemberId 검증에 사용하는 최소 JWT를 만든다. */
    private Jwt jwt(String subject) {
        return Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(subject)
                .issuedAt(java.time.Instant.now())
                .expiresAt(java.time.Instant.now().plusSeconds(60))
                .build();
    }
}
