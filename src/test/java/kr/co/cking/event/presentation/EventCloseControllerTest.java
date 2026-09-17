package kr.co.cking.event.presentation;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.event.application.ManualEventCloseService;
import kr.co.cking.event.application.service.EventClosingService;
import kr.co.cking.event.domain.EventErrorCode;
import kr.co.cking.event.domain.EventStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EventCloseController.class)
class EventCloseControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private ManualEventCloseService manualEventCloseService;

    @Test
    void closeReturnsAcceptedClosingResponseEnvelope() throws Exception {
        given(manualEventCloseService.close(1L, 10L))
                .willReturn(new EventClosingService.ClosingResult(10L, EventStatus.CLOSING));

        mockMvc.perform(post("/api/events/{eventId}/close", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.eventId").value(10))
                .andExpect(jsonPath("$.data.status").value("CLOSING"));
    }

    @Test
    void closeRejectsMissingUserId() throws Exception {
        mockMvc.perform(post("/api/events/{eventId}/close", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void closeMapsInvalidStateToConflictResponse() throws Exception {
        given(manualEventCloseService.close(1L, 10L))
                .willThrow(new BusinessException(EventErrorCode.INVALID_STATE));

        mockMvc.perform(post("/api/events/{eventId}/close", 10L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }
}
