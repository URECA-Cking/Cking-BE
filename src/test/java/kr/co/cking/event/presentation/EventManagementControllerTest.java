package kr.co.cking.event.presentation;

import kr.co.cking.event.application.CreatorEventService;
import kr.co.cking.event.application.EventReviewService;
import kr.co.cking.event.domain.DrawMethod;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventApprovalRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.stream.Stream;


import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
        Event event = new Event(1L, "팬미팅", "설명", Instant.now().plus(java.time.Duration.ofDays(1)),
                Instant.now().plus(java.time.Duration.ofDays(2)), 1, DrawMethod.WEIGHTED, 1L,
                "550e8400-e29b-41d4-a716-446655440000");
        ReflectionTestUtils.setField(event, "eventId", 1L);
        given(creatorEventService.create(any())).willReturn(event);

        mockMvc.perform(post("/api/creator/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":1,"requestId":"550e8400-e29b-41d4-a716-446655440000","title":"팬미팅","description":"설명","startAt":"2026-09-20T09:00:00Z","endAt":"2026-09-21T09:00:00Z","winnerCount":1,"drawMethod":"WEIGHTED"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.eventId").value(1))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
    }

    /** offset이 포함된 생성 시각을 UTC Instant로 정규화해 명령에 전달하는지 검증한다. */
    @Test
    void createEventNormalizesOffsetInstant() throws Exception {
        Event event = event(1L, "팬미팅");
        given(creatorEventService.create(any())).willAnswer(invocation -> {
            var command = invocation.getArgument(0, kr.co.cking.event.application.dto.CreateEventCommand.class);
            assertThat(command.startAt()).isEqualTo(Instant.parse("2026-09-20T09:00:00Z"));
            assertThat(command.endAt()).isEqualTo(Instant.parse("2026-09-21T09:00:00Z"));
            return event;
        });

        mockMvc.perform(post("/api/creator/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":1,"requestId":"550e8400-e29b-41d4-a716-446655440000","title":"팬미팅","startAt":"2026-09-20T18:00:00+09:00","endAt":"2026-09-21T18:00:00+09:00","winnerCount":1,"drawMethod":"WEIGHTED"}
                                """))
                .andExpect(status().isCreated());
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

    /** 두 Event 목록 API가 잘못된 페이지 값을 입력 검증 오류로 반환하는지 검증한다. */
    @ParameterizedTest
    @MethodSource("invalidPageRequests")
    void eventListsRejectInvalidPageRequests(String path, String parameter, String value) throws Exception {
        mockMvc.perform(get(path).param("userId", "1").param(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** Creator Event 목록 API가 문서에 정의된 운영 정보를 함께 반환하는지 검증한다. */
    @Test
    void creatorEventListReturnsDocumentedItemFields() throws Exception {
        Event event = event(7L, "팬미팅");
        given(creatorEventService.findMine(org.mockito.ArgumentMatchers.eq(1L), any()))
                .willReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(event)));

        mockMvc.perform(get("/api/creator/events").param("userId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].eventId").value(7))
                .andExpect(jsonPath("$.data.items[0].startAt").exists())
                .andExpect(jsonPath("$.data.items[0].endAt").exists())
                .andExpect(jsonPath("$.data.items[0].winnerCount").value(1))
                .andExpect(jsonPath("$.data.items[0].drawMethod").value("WEIGHTED"))
                .andExpect(jsonPath("$.data.items[0].createdAt").exists());
    }

    /** UUID 형식이 아닌 생성 requestId는 Controller 입력 검증에서 거부하는지 검증한다. */
    @Test
    void createEventRejectsNonUuidRequestId() throws Exception {
        mockMvc.perform(post("/api/creator/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":1,"requestId":"not-a-uuid","title":"팬미팅","startAt":"2026-09-20T09:00:00Z","endAt":"2026-09-21T09:00:00Z","winnerCount":1,"drawMethod":"WEIGHTED"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** Creator Event 삭제 API가 No Content를 반환하는지 검증한다. */
    @Test
    void deleteEventReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/creator/events/{eventId}", 1L).param("userId", "1"))
                .andExpect(status().isNoContent());
    }

    /** Event 수정 API가 변경된 초안 상태를 공통 응답 봉투로 반환하는지 검증한다. */
    @Test
    void updateEventReturnsDraftResultEnvelope() throws Exception {
        Event event = event(7L, "변경된 팬미팅");
        given(creatorEventService.update(any())).willReturn(event);

        mockMvc.perform(patch("/api/creator/events/{eventId}", 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":1,"title":"변경된 팬미팅","description":"변경 설명","startAt":"2026-09-20T09:00:00Z","endAt":"2026-09-21T09:00:00Z","winnerCount":2,"drawMethod":"WEIGHTED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.eventId").value(7))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
    }

    /** offset이 포함된 수정 시각을 UTC Instant로 정규화해 명령에 전달하는지 검증한다. */
    @Test
    void updateEventNormalizesOffsetInstant() throws Exception {
        Event event = event(7L, "변경된 팬미팅");
        given(creatorEventService.update(any())).willAnswer(invocation -> {
            var command = invocation.getArgument(0, kr.co.cking.event.application.dto.UpdateEventCommand.class);
            assertThat(command.startAt()).isEqualTo(Instant.parse("2026-09-20T09:00:00Z"));
            assertThat(command.endAt()).isEqualTo(Instant.parse("2026-09-21T09:00:00Z"));
            return event;
        });

        mockMvc.perform(patch("/api/creator/events/{eventId}", 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":1,"title":"변경된 팬미팅","description":"변경 설명","startAt":"2026-09-20T18:00:00+09:00","endAt":"2026-09-21T18:00:00+09:00","winnerCount":2,"drawMethod":"WEIGHTED"}
                                """))
                .andExpect(status().isOk());
    }

    /** 승인 요청 API가 승인 대기 상태를 공통 응답 봉투로 반환하는지 검증한다. */
    @Test
    void approvalRequestReturnsPendingApprovalResultEnvelope() throws Exception {
        mockMvc.perform(post("/api/creator/events/{eventId}/approval-request", 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.eventId").value(7))
                .andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"));
    }

    /** 관리자 승인 대기 목록 API가 Event와 승인 요청 정보를 페이지 봉투로 반환하는지 검증한다. */
    @Test
    void pendingEventListReturnsApprovalItemPageEnvelope() throws Exception {
        Event event = event(7L, "심사 대기 팬미팅");
        event.requestApproval();
        EventApprovalRequest request = new EventApprovalRequest(7L, 1, 1L);
        given(eventReviewService.findPending(org.mockito.ArgumentMatchers.eq(99L), any()))
                .willReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(
                        new EventReviewService.PendingEvent(request, event, "크리에이터"))));

        mockMvc.perform(get("/api/admin/events/pending").param("userId", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].eventId").value(7))
                .andExpect(jsonPath("$.data.items[0].creatorId").value(1))
                .andExpect(jsonPath("$.data.items[0].creatorName").value("크리에이터"))
                .andExpect(jsonPath("$.data.items[0].status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.data.items[0].approvalRound").value(1));
    }

    /** 관리자 승인 API가 예약 상태를 공통 응답 봉투로 반환하는지 검증한다. */
    @Test
    void approveEventReturnsScheduledResultEnvelope() throws Exception {
        mockMvc.perform(post("/api/admin/events/{eventId}/approve", 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":99}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.eventId").value(7))
                .andExpect(jsonPath("$.data.status").value("SCHEDULED"));
    }

    /** 관리자 거절 API가 거절 상태를 공통 응답 봉투로 반환하는지 검증한다. */
    @Test
    void rejectEventReturnsRejectedResultEnvelope() throws Exception {
        mockMvc.perform(post("/api/admin/events/{eventId}/reject", 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":99,\"rejectReason\":\"일정 조정이 필요합니다.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.eventId").value(7))
                .andExpect(jsonPath("$.data.status").value("REJECTED"));
    }

    private Event event(Long eventId, String title) {
        Event event = new Event(1L, title, "설명", Instant.now().plus(java.time.Duration.ofDays(1)),
                Instant.now().plus(java.time.Duration.ofDays(2)), 1, DrawMethod.WEIGHTED, 1L,
                "550e8400-e29b-41d4-a716-446655440000");
        ReflectionTestUtils.setField(event, "eventId", eventId);
        return event;
    }

    private static Stream<Arguments> invalidPageRequests() {
        return Stream.of(
                Arguments.of("/api/creator/events", "page", "-1"),
                Arguments.of("/api/creator/events", "size", "0"),
                Arguments.of("/api/creator/events", "size", "101"),
                Arguments.of("/api/admin/events/pending", "page", "-1"),
                Arguments.of("/api/admin/events/pending", "size", "0"),
                Arguments.of("/api/admin/events/pending", "size", "101")
        );
    }
}
