package kr.co.cking.sanction.presentation;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.sanction.application.SpaceCommentBlockService;
import kr.co.cking.sanction.application.dto.SpaceCommentBlockView;
import kr.co.cking.sanction.domain.SanctionErrorCode;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CreatorCommentBlockController.class)
@WithMockJwt(memberId = "10")
class CreatorCommentBlockControllerTest {

    private static final String URL = "/api/creator/comment-blocks";
    private static final Instant STARTED_AT = Instant.parse("2026-10-14T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SpaceCommentBlockService service;

    @MockitoBean
    private MemberRepository memberRepository;

    @Test
    void 차단은_201과_차단_정보를_돌려주고_호출자는_JWT로_식별한다() throws Exception {
        given(service.block(10L, 20L, 7)).willReturn(view(900L, true, null));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"memberId\": 20, \"days\": 7}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.sanctionId").value(900))
                .andExpect(jsonPath("$.data.memberId").value(20))
                .andExpect(jsonPath("$.data.memberName").value("차단된팬"))
                .andExpect(jsonPath("$.data.endsAt").value("2026-10-15T00:00:00Z"))
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.releasedAt").doesNotExist());
    }

    @Test
    void 차단_요청이_잘못되면_400이고_서비스를_부르지_않는다() throws Exception {
        for (String body : List.of(
                "{}",
                "{\"memberId\": 20}",
                "{\"days\": 7}",
                "{\"memberId\": 20, \"days\": 0}",
                "{\"memberId\": 20, \"days\": 366}")) {
            mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        then(service).should(never()).block(anyLong(), anyLong(), anyInt());
    }

    @Test
    void 자기_자신을_차단하면_400_CANNOT_BLOCK_SELF다() throws Exception {
        given(service.block(10L, 10L, 1)).willThrow(new BusinessException(SanctionErrorCode.CANNOT_BLOCK_SELF));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"memberId\": 10, \"days\": 1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANNOT_BLOCK_SELF"));
    }

    @Test
    void Creator가_아니면_차단이_403이다() throws Exception {
        given(service.block(10L, 20L, 1)).willThrow(new BusinessException(CommonErrorCode.FORBIDDEN));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"memberId\": 20, \"days\": 1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 없는_회원을_차단하면_404다() throws Exception {
        given(service.block(10L, 999L, 1)).willThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"memberId\": 999, \"days\": 1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void 목록을_공통_페이지_형식으로_돌려준다() throws Exception {
        PageRequest pageable = PageRequest.of(1, 10);
        given(service.findBlocks(10L, true, pageable))
                .willReturn(new PageImpl<>(List.of(view(900L, true, null)), pageable, 11));

        mockMvc.perform(get(URL).param("active", "true").param("page", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].sanctionId").value(900))
                .andExpect(jsonPath("$.data.items[0].memberName").value("차단된팬"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(11));
    }

    @Test
    void 목록_기본값은_전체_차단_0페이지_20건이다() throws Exception {
        given(service.findBlocks(10L, false, PageRequest.of(0, 20)))
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
    void Creator가_아니면_목록이_403이다() throws Exception {
        given(service.findBlocks(10L, false, PageRequest.of(0, 20)))
                .willThrow(new BusinessException(CommonErrorCode.FORBIDDEN));

        mockMvc.perform(get(URL))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 해제는_해제_시각을_담아_돌려준다() throws Exception {
        given(service.release(10L, 900L)).willReturn(view(900L, false, STARTED_AT.plusSeconds(3_600)));

        mockMvc.perform(post(URL + "/900/release"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sanctionId").value(900))
                .andExpect(jsonPath("$.data.active").value(false))
                .andExpect(jsonPath("$.data.releasedAt").value("2026-10-14T01:00:00Z"));
    }

    @Test
    void 남의_차단을_해제하면_404다() throws Exception {
        given(service.release(10L, 999L)).willThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        mockMvc.perform(post(URL + "/999/release"))
                .andExpect(status().isNotFound());
    }

    private static SpaceCommentBlockView view(Long id, boolean active, Instant releasedAt) {
        return new SpaceCommentBlockView(
                id, 20L, "차단된팬", STARTED_AT, STARTED_AT.plusSeconds(86_400), releasedAt, active);
    }
}
