package kr.co.cking.calendar.presentation;

import kr.co.cking.calendar.application.MemberCalendarEntryService;
import kr.co.cking.calendar.application.MemberCalendarQueryService;
import kr.co.cking.calendar.application.MemberCalendarScheduleResult;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MemberCalendarController.class)
class MemberCalendarControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberCalendarEntryService entryService;

    @MockitoBean
    private MemberCalendarQueryService queryService;

    @MockitoBean
    private MemberRepository memberRepository;

    @Test
    @WithMockJwt(memberId = "7")
    void 일정_담기는_성공_응답을_반환한다() throws Exception {
        mockMvc.perform(put("/api/me/calendar/schedules/{scheduleId}", 5L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data").doesNotExist());

        then(entryService).should().add(7L, 5L);
    }

    @Test
    void 일정_담기는_JWT가_없으면_401이다() throws Exception {
        mockMvc.perform(put("/api/me/calendar/schedules/{scheduleId}", 5L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @ParameterizedTest
    @WithMockJwt(memberId = "7")
    @ValueSource(longs = {0L, -1L})
    void 일정_담기는_scheduleId가_양수가_아니면_VALIDATION_FAILED를_반환한다(long invalidId) throws Exception {
        mockMvc.perform(put("/api/me/calendar/schedules/{scheduleId}", invalidId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 존재하지_않는_일정을_담으면_404를_반환한다() throws Exception {
        doThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND))
                .when(entryService).add(eq(7L), eq(999L));

        mockMvc.perform(put("/api/me/calendar/schedules/{scheduleId}", 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 일정_제거는_204를_반환한다() throws Exception {
        mockMvc.perform(delete("/api/me/calendar/schedules/{scheduleId}", 5L))
                .andExpect(status().isNoContent());

        then(entryService).should().remove(7L, 5L);
    }

    @Test
    void 일정_제거는_JWT가_없으면_401이다() throws Exception {
        mockMvc.perform(delete("/api/me/calendar/schedules/{scheduleId}", 5L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @ParameterizedTest
    @WithMockJwt(memberId = "7")
    @ValueSource(longs = {0L, -1L})
    void 일정_제거는_scheduleId가_양수가_아니면_VALIDATION_FAILED를_반환한다(long invalidId) throws Exception {
        mockMvc.perform(delete("/api/me/calendar/schedules/{scheduleId}", invalidId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 내_개인_캘린더_기간_조회는_JWT의_memberId를_서비스에_전달한다() throws Exception {
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        Instant to = Instant.parse("2026-10-31T00:00:00Z");
        MemberCalendarScheduleResult result = new MemberCalendarScheduleResult(
                100L, 5L, "테스트 크리에이터", "FAN_SIGN", "서울 팬사인회", "설명",
                Instant.parse("2026-10-10T05:00:00Z"), Instant.parse("2026-10-10T07:00:00Z"),
                "Asia/Seoul", "서울", "https://img/1.png", "https://example.com");
        given(queryService.findMine(7L, from, to)).willReturn(List.of(result));

        mockMvc.perform(get("/api/me/calendar/schedules")
                        .param("from", from.toString())
                        .param("to", to.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].scheduleId").value(100))
                .andExpect(jsonPath("$.data[0].creatorName").value("테스트 크리에이터"));
    }

    @Test
    void 내_개인_캘린더_기간_조회는_JWT가_없으면_401이다() throws Exception {
        mockMvc.perform(get("/api/me/calendar/schedules")
                        .param("from", "2026-10-01T00:00:00Z")
                        .param("to", "2026-10-31T00:00:00Z"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
