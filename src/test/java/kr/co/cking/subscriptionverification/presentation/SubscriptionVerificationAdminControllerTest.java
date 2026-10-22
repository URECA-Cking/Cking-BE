package kr.co.cking.subscriptionverification.presentation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.subscriptionverification.application.SubscriptionVerificationImageReuseQueryResult;
import kr.co.cking.subscriptionverification.application.SubscriptionVerificationQueryService;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationImageReuseType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SubscriptionVerificationAdminController.class)
class SubscriptionVerificationAdminControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private SubscriptionVerificationQueryService queryService;
    @MockitoBean private MemberRepository memberRepository;

    /** 관리자 요청은 hash나 이미지 없이 재사용 감사 결과만 반환한다. */
    @Test
    @WithMockJwt(memberId = "1")
    void 이미지_재사용_탐지_결과를_조회한다() throws Exception {
        given(queryService.getImageReuseForAdmin(1L, 123L)).willReturn(new SubscriptionVerificationImageReuseQueryResult(
                123L, 100L, SubscriptionVerificationImageReuseType.DIFFERENT_MEMBER,
                Instant.parse("2026-09-29T03:00:00Z")));

        mockMvc.perform(get("/api/admin/subscription-verifications/{verificationId}/image-reuse", 123L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.verificationId").value(123))
                .andExpect(jsonPath("$.data.matchedVerificationId").value(100))
                .andExpect(jsonPath("$.data.reuseType").value("DIFFERENT_MEMBER"))
                .andExpect(jsonPath("$.data.imageSha256").doesNotExist());
    }

    /** 양수가 아닌 Verification 식별자는 Service 호출 전에 요청 검증으로 거부한다. */
    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    @WithMockJwt(memberId = "1")
    void 잘못된_Verification_식별자를_거부한다(long verificationId) throws Exception {
        mockMvc.perform(get("/api/admin/subscription-verifications/{verificationId}/image-reuse", verificationId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        then(queryService).should(never()).getImageReuseForAdmin(any(), any());
    }
}
