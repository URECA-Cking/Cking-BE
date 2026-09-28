package kr.co.cking.calendar.presentation;

import kr.co.cking.calendar.application.CreatorScheduleService;
import kr.co.cking.calendar.domain.CreatorSchedule;
import kr.co.cking.calendar.domain.ScheduleType;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CreatorScheduleController.class)
class CreatorScheduleControllerTest {

    private static final String REQUEST_BODY = """
            {
              "scheduleType": "FAN_SIGN",
              "title": "서울 팬사인회",
              "description": "설명",
              "startAt": "2026-10-10T05:00:00Z",
              "endAt": "2026-10-10T07:00:00Z",
              "timeZone": "Asia/Seoul",
              "location": "서울",
              "imageUrl": "https://img/1.png",
              "externalUrl": "https://example.com"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreatorScheduleService scheduleService;

    @MockitoBean
    private MemberRepository memberRepository;

    @Test
    @WithMockJwt(memberId = "7")
    void 생성은_201과_생성된_일정을_반환한다() throws Exception {
        given(scheduleService.create(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.any()))
                .willReturn(schedule());

        mockMvc.perform(post("/api/creator/calendar/schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.scheduleId").value(100))
                .andExpect(jsonPath("$.data.scheduleType").value("FAN_SIGN"));
    }

    @Test
    void 생성은_JWT가_없으면_401이다() throws Exception {
        mockMvc.perform(post("/api/creator/calendar/schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 제목이_비어있으면_400과_VALIDATION_FAILED다() throws Exception {
        String invalid = """
                {
                  "scheduleType": "FAN_SIGN",
                  "title": "",
                  "startAt": "2026-10-10T05:00:00Z",
                  "endAt": "2026-10-10T07:00:00Z",
                  "timeZone": "Asia/Seoul"
                }
                """;

        mockMvc.perform(post("/api/creator/calendar/schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 수정은_소유권_검증을_거쳐_전체_필드를_교체한다() throws Exception {
        given(scheduleService.update(
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(5L), org.mockito.ArgumentMatchers.any()))
                .willReturn(schedule());

        mockMvc.perform(patch("/api/creator/calendar/schedules/{scheduleId}", 5L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scheduleId").value(100));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 다른_크리에이터_일정_수정은_403이다() throws Exception {
        given(scheduleService.update(
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(5L), org.mockito.ArgumentMatchers.any()))
                .willThrow(new BusinessException(CommonErrorCode.FORBIDDEN));

        mockMvc.perform(patch("/api/creator/calendar/schedules/{scheduleId}", 5L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 삭제는_204를_반환한다() throws Exception {
        mockMvc.perform(delete("/api/creator/calendar/schedules/{scheduleId}", 5L))
                .andExpect(status().isNoContent());

        then(scheduleService).should().delete(7L, 5L);
    }

    @Test
    @WithMockJwt(memberId = "7")
    void 내_일정_기간_조회는_JWT의_memberId를_서비스에_전달한다() throws Exception {
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        Instant to = Instant.parse("2026-10-31T00:00:00Z");
        given(scheduleService.findMine(7L, from, to)).willReturn(List.of(schedule()));

        mockMvc.perform(get("/api/creator/calendar/schedules")
                        .param("from", from.toString())
                        .param("to", to.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].scheduleId").value(100));
    }

    private CreatorSchedule schedule() {
        CreatorSchedule schedule = new CreatorSchedule(
                7L, ScheduleType.FAN_SIGN, "서울 팬사인회", "설명",
                Instant.parse("2026-10-10T05:00:00Z"), Instant.parse("2026-10-10T07:00:00Z"),
                "Asia/Seoul", "서울", "https://img/1.png", "https://example.com", Instant.now());
        org.springframework.test.util.ReflectionTestUtils.setField(schedule, "scheduleId", 100L);
        return schedule;
    }
}
