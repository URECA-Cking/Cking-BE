package kr.co.cking.event.presentation;

import kr.co.cking.event.application.CreatorEventService;
import kr.co.cking.event.application.EventReviewService;
import kr.co.cking.event.domain.DrawMethod;
import kr.co.cking.event.domain.Event;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EventManagementController.class)
class EventManagementControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private CreatorEventService creatorEventService;
    @MockitoBean private EventReviewService eventReviewService;

    /** Event 생성 API가 Created 상태와 공통 응답 봉투를 반환하는지 검증한다. */
    @Test
    void createEventReturnsCreatedEnvelope() throws Exception {
        Event event = new Event(1L, "팬미팅", "설명", LocalDateTime.now(ZoneOffset.UTC).plusDays(1),
                LocalDateTime.now(ZoneOffset.UTC).plusDays(2), 1, DrawMethod.WEIGHTED, 1L,
                "550e8400-e29b-41d4-a716-446655440000");
        ReflectionTestUtils.setField(event, "eventId", 1L);
        given(creatorEventService.create(any())).willReturn(event);

        mockMvc.perform(post("/api/creator/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":1,"requestId":"550e8400-e29b-41d4-a716-446655440000","title":"팬미팅","description":"설명","startAt":"2026-09-20T09:00:00","endAt":"2026-09-21T09:00:00","winnerCount":1,"drawMethod":"WEIGHTED"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.eventId").value(1))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
    }

    /** Creator Event 목록 API가 공통 페이지 봉투를 반환하는지 검증한다. */
    @Test
    void creatorEventListReturnsPageEnvelope() throws Exception {
        given(creatorEventService.findMine(org.mockito.ArgumentMatchers.eq(1L), any()))
                .willReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of()));

        mockMvc.perform(get("/api/creator/events").param("userId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }
}
