package kr.co.cking.member;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.member.presentation.MemberController;
import kr.co.cking.member.presentation.UserSelectionResponse;
import kr.co.cking.member.presentation.UserSummary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MemberController.class)
class MemberControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberQueryService memberQueryService;

    @Test
    void 사용자_목록을_공통_응답으로_반환한다() throws Exception {
        when(memberQueryService.findUsers()).thenReturn(List.of(new UserSummary(1L, "홍길동")));

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].userId").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("홍길동"));
    }

    @Test
    void 사용자_선택_요청을_공통_응답으로_반환한다() throws Exception {
        when(memberQueryService.selectUser(1L)).thenReturn(new UserSelectionResponse(1L, "홍길동", true));

        mockMvc.perform(post("/api/demo/users/select")
                        .contentType("application/json")
                        .content("{\"userId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.data.selected").value(true));
    }

    @Test
    void 존재하지_않는_사용자_선택은_404와_RESOURCE_NOT_FOUND를_반환한다() throws Exception {
        when(memberQueryService.selectUser(999L))
                .thenThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        mockMvc.perform(post("/api/demo/users/select")
                        .contentType("application/json")
                        .content("{\"userId\":999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }
}
