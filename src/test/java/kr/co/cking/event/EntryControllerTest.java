package kr.co.cking.event;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EntryController.class)
class EntryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private EventEntryService eventEntryService;

    @Test
    void 정상_응모는_SUCCESS_코드와_accepted_true를_반환한다() throws Exception {
        UUID requestId = UUID.randomUUID();
        EntryRequest request = new EntryRequest(1L, requestId, 2);
        EntryOutcome outcome = new EntryOutcome(EntryResultCode.SUCCESS, EntryResponse.accepted(requestId, 1L));
        when(eventEntryService.apply(eq(1L), any())).thenReturn(outcome);

        mockMvc.perform(post("/api/events/1/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.eventId").value(1))
                .andExpect(jsonPath("$.data.accepted").value(true))
                .andExpect(jsonPath("$.data.entryId").doesNotExist());
    }

    @Test
    void ticketCount가_0이면_400을_반환한다() throws Exception {
        EntryRequest request = new EntryRequest(1L, UUID.randomUUID(), 0);

        mockMvc.perform(post("/api/events/1/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void ticketCount가_100_초과면_400을_반환한다() throws Exception {
        EntryRequest request = new EntryRequest(1L, UUID.randomUUID(), 101);

        mockMvc.perform(post("/api/events/1/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
