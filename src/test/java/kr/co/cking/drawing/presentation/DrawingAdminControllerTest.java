package kr.co.cking.drawing.presentation;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.drawing.application.DrawingPublicationService;
import kr.co.cking.drawing.application.InitialDrawingPreparation;
import kr.co.cking.drawing.application.InitialDrawingPreparationService;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.domain.DrawingErrorCode;
import kr.co.cking.drawing.domain.DrawingSnapshotContract;
import kr.co.cking.drawing.domain.DrawingVisibility;
import kr.co.cking.event.domain.EventErrorCode;
import kr.co.cking.snapshot.domain.SnapshotErrorCode;
import kr.co.cking.snapshot.application.VerifiedSnapshot;
import kr.co.cking.snapshot.application.VerifiedSnapshotTestFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DrawingAdminController.class)
class DrawingAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InitialDrawingPreparationService initialDrawingPreparationService;

    @MockitoBean
    private DrawingPublicationService drawingPublicationService;

    @Test
    void 관리자는_공통_성공_응답으로_INITIAL_Drawing_준비_정보를_받는다() throws Exception {
        when(initialDrawingPreparationService.prepare(1L, 10L)).thenReturn(new InitialDrawingPreparation(
                VerifiedSnapshotTestFactory.create(20L, 10L, 2, "WEIGHTED", "WEIGHTED_V1")));

        mockMvc.perform(post("/api/admin/events/10/drawings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.eventId").value(10))
                .andExpect(jsonPath("$.data.snapshotId").value(20))
                .andExpect(jsonPath("$.data.winnerCount").value(2))
                .andExpect(jsonPath("$.data.drawMethod").value("WEIGHTED"))
                .andExpect(jsonPath("$.data.algorithmVersion").value("WEIGHTED_V1"))
                .andExpect(jsonPath("$.data.candidateCount").value(0));
    }

    @Test
    void 존재하지_않는_관리자는_RESOURCE_NOT_FOUND를_반환한다() throws Exception {
        when(initialDrawingPreparationService.prepare(999L, 10L))
                .thenThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        mockMvc.perform(post("/api/admin/events/10/drawings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void ADMIN이_아닌_사용자는_FORBIDDEN을_반환한다() throws Exception {
        when(initialDrawingPreparationService.prepare(2L, 10L))
                .thenThrow(new BusinessException(CommonErrorCode.FORBIDDEN));

        mockMvc.perform(post("/api/admin/events/10/drawings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":2}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 잘못된_Event_상태는_INVALID_STATE를_반환한다() throws Exception {
        when(initialDrawingPreparationService.prepare(1L, 10L))
                .thenThrow(new BusinessException(EventErrorCode.INVALID_STATE));

        mockMvc.perform(post("/api/admin/events/10/drawings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    @Test
    void 공식_Snapshot이_없으면_SNAPSHOT_NOT_FOUND를_반환한다() throws Exception {
        when(initialDrawingPreparationService.prepare(1L, 10L))
                .thenThrow(new BusinessException(SnapshotErrorCode.SNAPSHOT_NOT_FOUND));

        mockMvc.perform(post("/api/admin/events/10/drawings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SNAPSHOT_NOT_FOUND"));
    }

    @Test
    void Snapshot_Hash가_일치하지_않으면_SNAPSHOT_HASH_MISMATCH를_반환한다() throws Exception {
        when(initialDrawingPreparationService.prepare(1L, 10L))
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
    void 관리자는_완료된_Drawing_결과를_공개할_수_있다() throws Exception {
        Drawing drawing = completedDrawing();
        ReflectionTestUtils.setField(drawing, "visibility", DrawingVisibility.PUBLIC);
        when(drawingPublicationService.publish(5L, 1L)).thenReturn(drawing);

        mockMvc.perform(post("/api/admin/drawings/5/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.drawingId").value(10))
                .andExpect(jsonPath("$.data.eventId").value(10))
                .andExpect(jsonPath("$.data.visibility").value("PUBLIC"));
    }

    @Test
    void 완료되지_않은_Drawing_공개_요청은_DRAWING_NOT_COMPLETED를_반환한다() throws Exception {
        when(drawingPublicationService.publish(5L, 1L))
                .thenThrow(new BusinessException(DrawingErrorCode.DRAWING_NOT_COMPLETED));

        mockMvc.perform(post("/api/admin/drawings/5/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DRAWING_NOT_COMPLETED"));
    }

    private Drawing completedDrawing() {
        VerifiedSnapshot snapshot = VerifiedSnapshotTestFactory.create(20L, 10L, 2, "WEIGHTED", "WEIGHTED_V1");
        Drawing drawing = Drawing.createInitial(DrawingSnapshotContract.from(snapshot), 3L, 4L);
        ReflectionTestUtils.setField(drawing, "id", 10L);
        return drawing;
    }
}
