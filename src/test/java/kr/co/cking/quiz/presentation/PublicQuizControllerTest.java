package kr.co.cking.quiz.presentation;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.quiz.QuizFixtures;
import kr.co.cking.quiz.application.PublicQuizQueryService;
import kr.co.cking.quiz.application.dto.PublicQuizViews;
import kr.co.cking.quiz.domain.QuizErrorCode;
import kr.co.cking.quiz.presentation.dto.PublicQuizResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PublicQuizController.class)
class PublicQuizControllerTest {

    /** 공개 응답 필드 이름에 들어가면 안 되는 단어. 대소문자를 무시하고 부분 일치로 찾는다. */
    private static final List<String> FORBIDDEN_NAMES = List.of(
            "correct", "answer", "explanation", "evidence", "contenttext", "promptversion", "sha", "hash",
            "judge", "review", "decision");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PublicQuizQueryService queryService;

    @MockitoBean
    private MemberRepository memberRepository;

    @Test
    void 비로그인도_게시_목록을_조회한다() throws Exception {
        PageRequest pageable = PageRequest.of(0, 20);
        given(queryService.findPublished(10L, pageable)).willReturn(new PageImpl<>(List.of(
                new PublicQuizViews.Summary(1L, 100L, QuizFixtures.TITLE, 2, QuizFixtures.NOW)), pageable, 1));

        mockMvc.perform(get("/api/creators/10/quizzes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].quizId").value(1))
                .andExpect(jsonPath("$.data.items[0].revisionId").value(100))
                .andExpect(jsonPath("$.data.items[0].questionCount").value(2));
    }

    @Test
    void 공개_상세는_videoId와_문항_보기를_주고_정답_관련_필드는_없다() throws Exception {
        given(queryService.findPublishedDetail(10L, 1L)).willReturn(new PublicQuizViews.Detail(
                1L, 10L, 100L, QuizFixtures.VIDEO_ID, QuizFixtures.TITLE, QuizFixtures.NOW,
                List.of(new PublicQuizViews.Question(0, "물이 증발하면 무엇이 되나요?", List.of("구름", "돌")))));

        String body = mockMvc.perform(get("/api/creators/10/quizzes/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.videoId").value(QuizFixtures.VIDEO_ID))
                .andExpect(jsonPath("$.data.questions[0].question").value("물이 증발하면 무엇이 되나요?"))
                .andExpect(jsonPath("$.data.questions[0].options[0].optionNo").value(0))
                .andExpect(jsonPath("$.data.questions[0].options[0].text").value("구름"))
                .andReturn().getResponse().getContentAsString();

        Set<String> keys = new HashSet<>();
        collectKeys(new ObjectMapper().readTree(body), keys);
        assertThat(keys).contains("videoId", "questions", "options");
        assertThat(keys).doesNotContain("aggregation", "verdict", "reason", "evaluationId", "resultId", "judgeEvaluationId");
        keys.forEach(key -> assertThat(FORBIDDEN_NAMES).noneMatch(key.toLowerCase(Locale.ROOT)::contains));
    }

    private static void collectKeys(JsonNode node, Set<String> keys) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                keys.add(property.getKey());
                collectKeys(property.getValue(), keys);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectKeys(child, keys);
            }
        }
    }

    @Test
    void 공개_응답_DTO에는_정답_관련_필드가_정의돼_있지_않다() {
        Stream.of(PublicQuizResponse.Summary.class, PublicQuizResponse.Detail.class,
                        PublicQuizResponse.Question.class, PublicQuizResponse.Option.class,
                        PublicQuizViews.Summary.class, PublicQuizViews.Detail.class, PublicQuizViews.Question.class)
                .flatMap(type -> Arrays.stream(type.getRecordComponents()))
                .map(RecordComponent::getName)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .forEach(name -> assertThat(FORBIDDEN_NAMES).noneMatch(name::contains));
    }

    @Test
    void 미게시_게시취소_Quiz의_상세는_404다() throws Exception {
        given(queryService.findPublishedDetail(10L, 2L)).willThrow(new BusinessException(QuizErrorCode.QUIZ_NOT_FOUND));

        mockMvc.perform(get("/api/creators/10/quizzes/2"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUIZ_NOT_FOUND"));
    }

    @Test
    void 없는_Creator는_404다() throws Exception {
        given(queryService.findPublished(99L, PageRequest.of(0, 20)))
                .willThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        mockMvc.perform(get("/api/creators/99/quizzes"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }
}
