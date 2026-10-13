package kr.co.cking.sanction.presentation;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.sanction.application.SanctionQueryService;
import kr.co.cking.sanction.application.SanctionService;
import kr.co.cking.sanction.application.dto.SanctionView;
import kr.co.cking.sanction.domain.SanctionScope;
import kr.co.cking.sanction.domain.SanctionSource;
import kr.co.cking.sanction.domain.SanctionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminSanctionController.class)
@WithMockJwt(memberId = "1")
class AdminSanctionControllerTest {

    private static final String URL = "/api/admin/sanctions";
    private static final Instant STARTED_AT = Instant.parse("2026-10-13T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SanctionQueryService queryService;

    @MockitoBean
    private SanctionService commandService;

    @MockitoBean
    private MemberRepository memberRepository;

    @Test
    void 제재_목록을_공통_페이지_형식으로_돌려준다() throws Exception {
        PageRequest pageable = PageRequest.of(1, 10);
        given(queryService.findSanctions(1L, 20L, true, pageable))
                .willReturn(new PageImpl<>(List.of(view(900L, SanctionSource.AUTOMATIC, 500L, null, true)), pageable, 11));

        mockMvc.perform(get(URL).param("memberId", "20").param("active", "true").param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].sanctionId").value(900))
                .andExpect(jsonPath("$.data.items[0].memberId").value(20))
                .andExpect(jsonPath("$.data.items[0].scope").value("PLATFORM"))
                .andExpect(jsonPath("$.data.items[0].type").value("COMMENT_RESTRICTION"))
                .andExpect(jsonPath("$.data.items[0].source").value("AUTOMATIC"))
                .andExpect(jsonPath("$.data.items[0].causeCommentId").value(500))
                .andExpect(jsonPath("$.data.items[0].active").value(true))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(11));
    }

    @Test
    void 목록_기본값은_전체_회원_전체_제재_0페이지_20건이다() throws Exception {
        given(queryService.findSanctions(1L, null, false, PageRequest.of(0, 20)))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void 페이지_크기가_범위를_벗어나면_400이다() throws Exception {
        mockMvc.perform(get(URL).param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 관리자가_아니면_목록이_403이다() throws Exception {
        given(queryService.findSanctions(1L, null, false, PageRequest.of(0, 20)))
                .willThrow(new BusinessException(CommonErrorCode.FORBIDDEN));

        mockMvc.perform(get(URL))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 수동_제한은_201과_제한_정보를_돌려준다() throws Exception {
        given(commandService.createManual(1L, 20L, 5, "반복 욕설"))
                .willReturn(view(901L, SanctionSource.MANUAL, null, "반복 욕설", true));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\": 20, \"days\": 5, \"reason\": \"반복 욕설\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.sanctionId").value(901))
                .andExpect(jsonPath("$.data.source").value("MANUAL"))
                .andExpect(jsonPath("$.data.reason").value("반복 욕설"))
                .andExpect(jsonPath("$.data.causeCommentId").doesNotExist());
    }

    @Test
    void 수동_제한_요청이_잘못되면_400이고_서비스를_부르지_않는다() throws Exception {
        for (String body : List.of(
                "{}",
                "{\"memberId\": 20, \"days\": 0, \"reason\": \"사유\"}",
                "{\"memberId\": 20, \"days\": 366, \"reason\": \"사유\"}",
                "{\"memberId\": 20, \"days\": 5, \"reason\": \" \"}",
                "{\"days\": 5, \"reason\": \"사유\"}")) {
            mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        then(commandService).should(never()).createManual(anyLong(), anyLong(), anyInt(), anyString());
    }

    @Test
    void 없는_회원에게_수동_제한을_걸면_404다() throws Exception {
        given(commandService.createManual(1L, 999L, 1, "사유"))
                .willThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\": 999, \"days\": 1, \"reason\": \"사유\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void 해제는_해제_시각과_관리자를_담아_돌려준다() throws Exception {
        given(commandService.release(1L, 900L)).willReturn(new SanctionView(
                900L, 20L, SanctionScope.PLATFORM, SanctionType.COMMENT_RESTRICTION, SanctionSource.AUTOMATIC, 500L, null,
                STARTED_AT, STARTED_AT.plusSeconds(86_400), STARTED_AT.plusSeconds(3_600), 1L, 1L, false));

        mockMvc.perform(post(URL + "/900/release"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sanctionId").value(900))
                .andExpect(jsonPath("$.data.active").value(false))
                .andExpect(jsonPath("$.data.releasedBy").value(1))
                .andExpect(jsonPath("$.data.releasedAt").value("2026-10-13T01:00:00Z"));
    }

    @Test
    void 없는_제재를_해제하면_404다() throws Exception {
        given(commandService.release(1L, 999L)).willThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        mockMvc.perform(post(URL + "/999/release"))
                .andExpect(status().isNotFound());
    }

    private static SanctionView view(Long id, SanctionSource source, Long causeCommentId, String reason, boolean active) {
        return new SanctionView(
                id, 20L, SanctionScope.PLATFORM, SanctionType.COMMENT_RESTRICTION, source, causeCommentId, reason,
                STARTED_AT, STARTED_AT.plusSeconds(86_400), null, 1L, null, active);
    }
}
