package kr.co.cking.stream.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.stream.application.DeadStreamAdminService;
import kr.co.cking.stream.domain.DeadStreamMessage;
import kr.co.cking.stream.domain.DeadStreamResolutionStatus;
import kr.co.cking.stream.domain.DeadStreamType;

@WebMvcTest(DeadStreamAdminController.class)
class DeadStreamAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeadStreamAdminService deadStreamAdminService;

    private static DeadStreamMessage message(DeadStreamResolutionStatus status) {
        DeadStreamMessage message = DeadStreamMessage.builder()
                .sourceStreamId("1700000000000-0")
                .streamType(DeadStreamType.SPEND)
                .payload("{\"secret\":\"do-not-expose\"}")
                .requestId("req-1")
                .eventId(3L)
                .memberId(7L)
                .failureReason("PEL 최대 재시도 초과")
                .retryCount(6)
                .lastFailedAt(Instant.parse("2026-09-21T00:00:00Z"))
                .resolutionStatus(DeadStreamResolutionStatus.UNRESOLVED)
                .createdAt(Instant.parse("2026-09-21T00:00:00Z"))
                .build();
        if (status == DeadStreamResolutionStatus.RESOLVED) {
            message.resolve(1L, Instant.parse("2026-09-21T01:00:00Z"));
        }
        return message;
    }

    @Test
    void 목록은_기본값으로_UNRESOLVED_첫_페이지를_반환하고_payload는_노출하지_않는다() throws Exception {
        when(deadStreamAdminService.list(1L, DeadStreamResolutionStatus.UNRESOLVED, 0, 20))
                .thenReturn(new PageImpl<>(List.of(message(DeadStreamResolutionStatus.UNRESOLVED)),
                        PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/admin/dead-streams").param("userId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].streamType").value("SPEND"))
                .andExpect(jsonPath("$.data.items[0].eventId").value(3))
                .andExpect(jsonPath("$.data.items[0].resolutionStatus").value("UNRESOLVED"))
                .andExpect(jsonPath("$.data.items[0].payload").doesNotExist())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void status_page_size를_전달한다() throws Exception {
        when(deadStreamAdminService.list(1L, DeadStreamResolutionStatus.RESOLVED, 2, 50))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 50), 0));

        mockMvc.perform(get("/api/admin/dead-streams")
                        .param("userId", "1").param("status", "RESOLVED").param("page", "2").param("size", "50"))
                .andExpect(status().isOk());

        verify(deadStreamAdminService).list(1L, DeadStreamResolutionStatus.RESOLVED, 2, 50);
    }

    @Test
    void 잘못된_입력은_서비스_호출_없이_400이다() throws Exception {
        mockMvc.perform(get("/api/admin/dead-streams")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/dead-streams").param("userId", "1").param("size", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/dead-streams").param("userId", "1").param("status", "UNKNOWN"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/admin/dead-streams/1/replay")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(deadStreamAdminService);
    }

    @Test
    void replay는_처리된_메시지를_반환한다() throws Exception {
        when(deadStreamAdminService.replay(eq(1L), eq(5L)))
                .thenReturn(message(DeadStreamResolutionStatus.RESOLVED));

        mockMvc.perform(post("/api/admin/dead-streams/5/replay")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resolutionStatus").value("RESOLVED"))
                .andExpect(jsonPath("$.data.resolvedBy").value(1));
    }

    @Test
    void 없는_메시지는_404이고_ADMIN이_아니면_403이다() throws Exception {
        when(deadStreamAdminService.replay(eq(1L), eq(404L)))
                .thenThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        when(deadStreamAdminService.replay(eq(2L), any()))
                .thenThrow(new BusinessException(CommonErrorCode.FORBIDDEN));

        mockMvc.perform(post("/api/admin/dead-streams/404/replay")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":1}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/admin/dead-streams/5/replay")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":2}"))
                .andExpect(status().isForbidden());
    }
}
