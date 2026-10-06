package kr.co.cking.interest.presentation;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import kr.co.cking.interest.application.InterestQueryService;
import kr.co.cking.interest.application.dto.SelectableInterests;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InterestController.class)
class InterestControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean InterestQueryService queryService;

    @Test
    void 선택_가능한_관심_분야를_분류체계_버전과_함께_반환한다() throws Exception {
        given(queryService.findSelectable()).willReturn(new SelectableInterests("v0.2", 3, List.of(
                new SelectableInterests.Item("FITNESS", "운동·건강", 1),
                new SelectableInterests.Item("FOOD", "요리·푸드", 2))));

        mockMvc.perform(get("/api/interests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.taxonomyVersion").value("v0.2"))
                .andExpect(jsonPath("$.data.maxSelection").value(3))
                .andExpect(jsonPath("$.data.items[0].interestCode").value("FITNESS"))
                .andExpect(jsonPath("$.data.items[0].name").value("운동·건강"))
                .andExpect(jsonPath("$.data.items[1].displayOrder").value(2))
                .andExpect(jsonPath("$.data.items[0].description").doesNotExist());
    }

    @Test
    void 활성_분류체계가_없으면_빈_목록을_정상_응답한다() throws Exception {
        given(queryService.findSelectable()).willReturn(new SelectableInterests(null, 3, List.of()));

        mockMvc.perform(get("/api/interests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taxonomyVersion").doesNotExist())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }
}
