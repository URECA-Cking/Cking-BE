package kr.co.cking.post.presentation;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.post.application.AdminCommentDeleteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminCommentController.class)
@WithMockJwt(memberId = "1")
class AdminCommentControllerTest {

    private static final String URL = "/api/admin/comments/500";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminCommentDeleteService deleteService;

    @MockitoBean
    private MemberRepository memberRepository;

    @Test
    void 삭제는_204이고_호출자는_JWT로_식별한다() throws Exception {
        mockMvc.perform(delete(URL))
                .andExpect(status().isNoContent());

        verify(deleteService).delete(1L, 500L);
    }

    @Test
    void 없는_댓글이면_404다() throws Exception {
        willThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND)).given(deleteService).delete(1L, 500L);

        mockMvc.perform(delete(URL))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void 관리자가_아니면_403이다() throws Exception {
        willThrow(new BusinessException(CommonErrorCode.FORBIDDEN)).given(deleteService).delete(1L, 500L);

        mockMvc.perform(delete(URL))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 댓글_ID가_양수가_아니면_400이다() throws Exception {
        mockMvc.perform(delete("/api/admin/comments/0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
