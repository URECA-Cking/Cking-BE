package kr.co.cking.creator.presentation;

import kr.co.cking.common.security.WithMockJwt;
import kr.co.cking.creator.application.CreatorRecommendationQueryService;
import kr.co.cking.creator.application.dto.PersonalizedCreatorRecommendationView;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CreatorRecommendationController.class)
class CreatorRecommendationControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean CreatorRecommendationQueryService queryService;
    @MockitoBean MemberRepository memberRepository;

    @Test
    @WithMockJwt(memberId = "7")
    void 기본_size_10으로_개인화_추천_카드를_반환한다() throws Exception {
        given(queryService.findForMember(7L, 10)).willReturn(new PersonalizedCreatorRecommendationView(
                "HYBRID_PERSONALIZED_V1",
                List.of(new PersonalizedCreatorRecommendationView.Item(
                        20L,
                        "추천 크리에이터",
                        "소개",
                        "https://example.com/profile.png",
                        new BigDecimal("0.01626124"),
                        List.of("FITNESS", "FOOD"),
                        List.of(1L, 2L)))));

        mockMvc.perform(get("/api/me/creator-recommendations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.policyVersion").value("HYBRID_PERSONALIZED_V1"))
                .andExpect(jsonPath("$.data.items[0].creatorId").value(20))
                .andExpect(jsonPath("$.data.items[0].creatorName").value("추천 크리에이터"))
                .andExpect(jsonPath("$.data.items[0].introText").value("소개"))
                .andExpect(jsonPath("$.data.items[0].profileImageUrl")
                        .value("https://example.com/profile.png"))
                .andExpect(jsonPath("$.data.items[0].aggregateScore").value(0.01626124))
                .andExpect(jsonPath("$.data.items[0].interestCodes[0]").value("FITNESS"))
                .andExpect(jsonPath("$.data.items[0].interestCodes[1]").value("FOOD"))
                .andExpect(jsonPath("$.data.items[0].seedCreatorIds[0]").value(1))
                .andExpect(jsonPath("$.data.items[0].seedCreatorIds[1]").value(2));

        then(queryService).should().findForMember(7L, 10);
    }

    @Test
    @WithMockJwt(memberId = "7")
    void size는_1부터_20까지다() throws Exception {
        mockMvc.perform(get("/api/me/creator-recommendations").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/me/creator-recommendations").param("size", "21"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        then(queryService).should(never()).findForMember(7L, 0);
        then(queryService).should(never()).findForMember(7L, 21);
    }

    @Test
    void JWT가_없으면_401이다() throws Exception {
        mockMvc.perform(get("/api/me/creator-recommendations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
