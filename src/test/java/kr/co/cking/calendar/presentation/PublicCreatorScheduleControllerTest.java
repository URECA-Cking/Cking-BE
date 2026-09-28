package kr.co.cking.calendar.presentation;

import kr.co.cking.calendar.application.CreatorScheduleQueryService;
import kr.co.cking.calendar.domain.CreatorSchedule;
import kr.co.cking.calendar.domain.ScheduleType;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PublicCreatorScheduleController.class)
class PublicCreatorScheduleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreatorScheduleQueryService queryService;

    @Test
    void 기간_조회는_인증_없이_허용된다() throws Exception {
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        Instant to = Instant.parse("2026-10-31T00:00:00Z");
        given(queryService.findByCreatorId(42L, from, to)).willReturn(List.of(schedule()));

        mockMvc.perform(get("/api/creators/{creatorId}/calendar/schedules", 42L)
                        .param("from", from.toString())
                        .param("to", to.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].scheduleId").value(100));
    }

    @Test
    void 존재하지_않는_크리에이터는_404다() throws Exception {
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        Instant to = Instant.parse("2026-10-31T00:00:00Z");
        given(queryService.findByCreatorId(42L, from, to))
                .willThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        mockMvc.perform(get("/api/creators/{creatorId}/calendar/schedules", 42L)
                        .param("from", from.toString())
                        .param("to", to.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void 상세_조회는_인증_없이_허용된다() throws Exception {
        given(queryService.findDetail(42L, 100L)).willReturn(schedule());

        mockMvc.perform(get("/api/creators/{creatorId}/calendar/schedules/{scheduleId}", 42L, 100L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("서울 팬사인회"));
    }

    private CreatorSchedule schedule() {
        CreatorSchedule schedule = new CreatorSchedule(
                42L, ScheduleType.FAN_SIGN, "서울 팬사인회", "설명",
                Instant.parse("2026-10-10T05:00:00Z"), Instant.parse("2026-10-10T07:00:00Z"),
                "Asia/Seoul", "서울", "https://img/1.png", "https://example.com", Instant.now());
        ReflectionTestUtils.setField(schedule, "scheduleId", 100L);
        return schedule;
    }
}
