package kr.co.cking.creator.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Set;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.recommendation.application.CreatorIntroductions;
import kr.co.cking.recommendation.application.RecommendationEligibilityService;
import kr.co.cking.recommendation.application.TaxonomyCodes;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

class RecommendationProofStorageTest {
    @Test
    void 적재는_입력_근거를_검증하고_현재_소개_불일치는_조회에서_검사한다() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var taxonomy = mock(TaxonomyCodes.class);
        when(taxonomy.activeCodes("v0.2", "a".repeat(64))).thenReturn(Set.of("FOOD"));
        var service = new RecommendationEligibilityService(jdbc, taxonomy, mock(CreatorIntroductions.class));
        var proof = new ObjectMapper().readTree("""
            {"policyVersion":"RELATED_V1","taxonomyVersion":"v0.2","taxonomyHash":"%s",
             "profiles":[{"creatorId":10,"introductionHash":"%s","tags":["FOOD"]}]}
            """.formatted("a".repeat(64), "b".repeat(64)));
        service.validateForStorage(proof, null, Set.of(10L), "FOOD");
        verifyNoInteractions(jdbc);
        assertThatThrownBy(() -> service.validateForStorage(proof, null, Set.of(10L), "FITNESS"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 비활성_분류체계는_적재할_수_없다() throws Exception {
        var taxonomy = mock(TaxonomyCodes.class);
        when(taxonomy.activeCodes(anyString(), anyString())).thenReturn(Set.of());
        var service = new RecommendationEligibilityService(mock(JdbcTemplate.class), taxonomy, mock(CreatorIntroductions.class));
        var proof = new ObjectMapper().readTree("""
            {"policyVersion":"RELATED_V1","taxonomyVersion":"v0.2","taxonomyHash":"%s","profiles":[]}
            """.formatted("a".repeat(64)));
        assertThatThrownBy(() -> service.validateForStorage(proof, null, Set.of(), "FOOD"))
                .isInstanceOf(BusinessException.class);
    }
}
