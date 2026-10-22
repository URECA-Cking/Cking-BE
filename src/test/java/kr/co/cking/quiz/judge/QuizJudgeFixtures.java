package kr.co.cking.quiz.judge;

import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** Judge 테스트용 입력과 Provider 응답 JSON. */
public final class QuizJudgeFixtures {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private QuizJudgeFixtures() {
    }

    public static QuizJudgeInput input(int questionCount) {
        List<QuizJudgeInput.Question> questions = new ArrayList<>();
        for (int no = 0; no < questionCount; no++) {
            questions.add(new QuizJudgeInput.Question(no, "질문 " + no,
                    List.of("보기 A" + no, "보기 B" + no, "보기 C" + no, "보기 D" + no), no % 4,
                    "해설 " + no, "근거 " + no));
        }
        return new QuizJudgeInput("물은 증발해 구름이 되고 비가 되어 내린다.", questions);
    }

    /** 문항 0..count-1을 모두 pass로 평가한 응답 JSON. */
    public static String allPass(int questionCount) {
        return output(questionCount, (no, rubric) -> new String[] {"pass", "근거가 원문과 일치합니다."});
    }

    /** {@code verdictAndReason(questionNo, rubric)}가 돌려준 {verdict, reason}으로 만든 응답 JSON. */
    public static String output(int questionCount, BiFunction<Integer, JudgeRubric, String[]> verdictAndReason) {
        List<Map<String, Object>> questions = new ArrayList<>();
        for (int no = 0; no < questionCount; no++) {
            questions.add(question(no, verdictAndReason));
        }
        return OBJECT_MAPPER.writeValueAsString(Map.of("questions", questions));
    }

    public static Map<String, Object> question(int no, BiFunction<Integer, JudgeRubric, String[]> verdictAndReason) {
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("questionNo", no);
        for (JudgeRubric rubric : JudgeRubric.values()) {
            String[] value = verdictAndReason.apply(no, rubric);
            question.put(rubric.field(), Map.of("verdict", value[0], "reason", value[1]));
        }
        return question;
    }

    public static String json(Object value) {
        return OBJECT_MAPPER.writeValueAsString(value);
    }
}
