package kr.co.cking.drawing.presentation;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.drawing.application.DrawingAdminQueryService;
import kr.co.cking.drawing.application.DrawingPublicationResult;
import kr.co.cking.drawing.application.DrawingPublicationResult.PublicationOutcome;
import kr.co.cking.drawing.application.DrawingQueryResult;
import kr.co.cking.drawing.application.DrawingResultQuery;
import kr.co.cking.drawing.application.DrawingWinnerResult;
import kr.co.cking.drawing.application.InitialDrawingExecutionService;
import kr.co.cking.drawing.application.InitialDrawingResult;
import kr.co.cking.drawing.application.PublicationService;
import kr.co.cking.drawing.domain.DrawingErrorCode;
import kr.co.cking.drawing.domain.DrawingStatus;
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.drawing.domain.DrawingVisibility;
import kr.co.cking.event.domain.EventErrorCode;
import kr.co.cking.snapshot.domain.SnapshotErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DrawingAdminController.class)
class DrawingAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InitialDrawingExecutionService initialDrawingExecutionService;

    @MockitoBean
    private PublicationService publicationService;

    @MockitoBean
    private DrawingAdminQueryService drawingAdminQueryService;

    @Test
    void 관리자는_공통_성공_응답으로_INITIAL_Drawing_실행_결과를_받는다() throws Exception {
        when(initialDrawingExecutionService.execute(1L, 10L)).thenReturn(
                new InitialDrawingResult(20L, 10L, DrawingStatus.COMPLETED, 2));

        mockMvc.perform(post("/api/admin/events/10/drawings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.drawingId").value(20))
                .andExpect(jsonPath("$.data.eventId").value(10))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.winnerCount").value(2))
                .andExpect(jsonPath("$.data.snapshotId").doesNotExist());
    }

    @Test
    void 존재하지_않는_관리자는_RESOURCE_NOT_FOUND를_반환한다() throws Exception {
        when(initialDrawingExecutionService.execute(999L, 10L))
                .thenThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        mockMvc.perform(post("/api/admin/events/10/drawings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void ADMIN이_아닌_사용자는_FORBIDDEN을_반환한다() throws Exception {
        when(initialDrawingExecutionService.execute(2L, 10L))
                .thenThrow(new BusinessException(CommonErrorCode.FORBIDDEN));

        mockMvc.perform(post("/api/admin/events/10/drawings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":2}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 잘못된_Event_상태는_INVALID_STATE를_반환한다() throws Exception {
        when(initialDrawingExecutionService.execute(1L, 10L))
                .thenThrow(new BusinessException(EventErrorCode.INVALID_STATE));

        mockMvc.perform(post("/api/admin/events/10/drawings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    @Test
    void 진행중인_INITIAL_Drawing이_있으면_CONCURRENT_COMMAND를_반환한다() throws Exception {
        when(initialDrawingExecutionService.execute(1L, 10L))
                .thenThrow(new BusinessException(DrawingErrorCode.CONCURRENT_COMMAND));

        mockMvc.perform(post("/api/admin/events/10/drawings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_COMMAND"));
    }

    @Test
    void 공식_Snapshot이_없으면_SNAPSHOT_NOT_FOUND를_반환한다() throws Exception {
        when(initialDrawingExecutionService.execute(1L, 10L))
                .thenThrow(new BusinessException(SnapshotErrorCode.SNAPSHOT_NOT_FOUND));

        mockMvc.perform(post("/api/admin/events/10/drawings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SNAPSHOT_NOT_FOUND"));
    }

    @Test
    void Snapshot_Hash가_일치하지_않으면_SNAPSHOT_HASH_MISMATCH를_반환한다() throws Exception {
        when(initialDrawingExecutionService.execute(1L, 10L))
                .thenThrow(new BusinessException(SnapshotErrorCode.SNAPSHOT_HASH_MISMATCH));

        mockMvc.perform(post("/api/admin/events/10/drawings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SNAPSHOT_HASH_MISMATCH"));
    }

    @Test
    void userId가_없거나_식별자가_양수가_아니면_VALIDATION_FAILED를_반환한다() throws Exception {
        mockMvc.perform(post("/api/admin/events/10/drawings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/admin/events/0/drawings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 관리자는_공통응답으로_Drawing_기본정보를_조회한다() throws Exception {
        when(drawingAdminQueryService.getDrawing(20L, 1L)).thenReturn(new DrawingQueryResult(
                20L, 10L, 30L, 0, DrawingType.INITIAL, DrawingStatus.COMPLETED,
                DrawingVisibility.PRIVATE, "WEIGHTED", "WEIGHTED_V1", 2, 1L,
                Instant.parse("2026-09-17T00:00:00Z"),
                Instant.parse("2026-09-17T00:01:00Z"),
                Instant.parse("2026-09-17T00:02:00Z"),
                null
        ));

        mockMvc.perform(get("/api/admin/drawings/20").param("userId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.drawingId").value(20))
                .andExpect(jsonPath("$.data.eventId").value(10))
                .andExpect(jsonPath("$.data.snapshotId").value(30))
                .andExpect(jsonPath("$.data.drawType").value("INITIAL"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.data.drawMethod").value("WEIGHTED"))
                .andExpect(jsonPath("$.data.algorithmVersion").value("WEIGHTED_V1"));
    }

    @Test
    void 관리자는_순위순으로_Drawing_결과를_조회한다() throws Exception {
        when(drawingAdminQueryService.getDrawingResult(20L, 1L)).thenReturn(new DrawingResultQuery(20L, List.of(
                new DrawingWinnerResult(100L, 10L, 20L, 2L, "둘", "010-0000-0002", "two@example.com", 1, 7L,
                        Instant.parse("2026-09-17T00:02:00Z")),
                new DrawingWinnerResult(101L, 10L, 20L, 3L, "셋", "010-0000-0003", "three@example.com", 2, 3L,
                        Instant.parse("2026-09-17T00:02:00Z"))
        )));

        mockMvc.perform(get("/api/admin/drawings/20/result").param("userId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.drawingId").value(20))
                .andExpect(jsonPath("$.data.winners[0].winnerId").value(100))
                .andExpect(jsonPath("$.data.winners[0].userId").value(2))
                .andExpect(jsonPath("$.data.winners[0].name").value("둘"))
                .andExpect(jsonPath("$.data.winners[0].phone").value("010-0000-0002"))
                .andExpect(jsonPath("$.data.winners[0].email").value("two@example.com"))
                .andExpect(jsonPath("$.data.winners[0].rankInDrawing").value(1))
                .andExpect(jsonPath("$.data.winners[0].appliedTicketCount").value(7))
                .andExpect(jsonPath("$.data.winners[1].rankInDrawing").value(2));
    }

    @Test
    void Drawing_조회는_권한과_완료상태_오류를_공통오류응답으로_반환한다() throws Exception {
        when(drawingAdminQueryService.getDrawing(20L, 999L))
                .thenThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        when(drawingAdminQueryService.getDrawing(20L, 2L))
                .thenThrow(new BusinessException(CommonErrorCode.FORBIDDEN));
        when(drawingAdminQueryService.getDrawingResult(20L, 1L))
                .thenThrow(new BusinessException(DrawingErrorCode.DRAWING_NOT_COMPLETED));

        mockMvc.perform(get("/api/admin/drawings/20").param("userId", "999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/admin/drawings/20").param("userId", "2"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/admin/drawings/20/result").param("userId", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DRAWING_NOT_COMPLETED"));
    }

    @Test
    void Drawing_조회_식별자가_누락되거나_양수가_아니면_VALIDATION_FAILED를_반환한다() throws Exception {
        mockMvc.perform(get("/api/admin/drawings/20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/admin/drawings/0/result").param("userId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/admin/drawings/20/result").param("userId", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 관리자는_공통_성공_응답으로_Drawing을_공개한다() throws Exception {
        Instant publishedAt = Instant.parse("2026-09-18T12:00:00Z");
        when(publicationService.publish(10L, 1L)).thenReturn(new DrawingPublicationResult(
                10L, 20L, DrawingVisibility.PUBLIC, publishedAt, PublicationOutcome.PUBLISHED));

        mockMvc.perform(post("/api/admin/drawings/10/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.drawingId").value(10))
                .andExpect(jsonPath("$.data.eventId").value(20))
                .andExpect(jsonPath("$.data.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.data.publishedAt").value("2026-09-18T12:00:00Z"));

        verify(publicationService).publish(10L, 1L);
    }

    @Test
    void 존재하지_않는_관리자의_Drawing_공개_요청은_RESOURCE_NOT_FOUND를_반환한다() throws Exception {
        when(publicationService.publish(10L, 999L))
                .thenThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        mockMvc.perform(post("/api/admin/drawings/10/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void ADMIN이_아닌_사용자의_Drawing_공개_요청은_FORBIDDEN을_반환한다() throws Exception {
        when(publicationService.publish(10L, 2L))
                .thenThrow(new BusinessException(CommonErrorCode.FORBIDDEN));

        mockMvc.perform(post("/api/admin/drawings/10/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":2}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 존재하지_않는_Drawing_공개_요청은_DRAWING_NOT_FOUND를_반환한다() throws Exception {
        when(publicationService.publish(10L, 1L))
                .thenThrow(new BusinessException(DrawingErrorCode.DRAWING_NOT_FOUND));

        mockMvc.perform(post("/api/admin/drawings/10/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DRAWING_NOT_FOUND"));
    }

    @Test
    void 완료되지_않은_Drawing_공개_요청은_DRAWING_NOT_COMPLETED를_반환한다() throws Exception {
        when(publicationService.publish(10L, 1L))
                .thenThrow(new BusinessException(DrawingErrorCode.DRAWING_NOT_COMPLETED));

        mockMvc.perform(post("/api/admin/drawings/10/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DRAWING_NOT_COMPLETED"));
    }

    @Test
    void INITIAL이_아닌_Drawing_공개_요청은_DRAWING_TYPE_NOT_SUPPORTED를_반환한다() throws Exception {
        when(publicationService.publish(10L, 1L))
                .thenThrow(new BusinessException(DrawingErrorCode.DRAWING_TYPE_NOT_SUPPORTED));

        mockMvc.perform(post("/api/admin/drawings/10/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DRAWING_TYPE_NOT_SUPPORTED"));
    }

    @Test
    void 잘못된_공개_상태는_INVALID_STATE를_반환한다() throws Exception {
        when(publicationService.publish(10L, 1L))
                .thenThrow(new BusinessException(EventErrorCode.INVALID_STATE));

        mockMvc.perform(post("/api/admin/drawings/10/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    @Test
    void 이미_공개된_Drawing_재요청도_현재_공개_상태를_성공_응답으로_반환한다() throws Exception {
        Instant publishedAt = Instant.parse("2026-09-18T12:00:00Z");
        when(publicationService.publish(10L, 1L)).thenReturn(new DrawingPublicationResult(
                10L, 20L, DrawingVisibility.PUBLIC, publishedAt, PublicationOutcome.ALREADY_PUBLISHED));

        mockMvc.perform(post("/api/admin/drawings/10/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.drawingId").value(10))
                .andExpect(jsonPath("$.data.eventId").value(20))
                .andExpect(jsonPath("$.data.visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.data.publishedAt").value("2026-09-18T12:00:00Z"));

        verify(publicationService).publish(10L, 1L);
    }

    @Test
    void 공개_요청의_userId_또는_drawingId가_양수가_아니면_VALIDATION_FAILED를_반환한다() throws Exception {
        mockMvc.perform(post("/api/admin/drawings/10/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/admin/drawings/0/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
