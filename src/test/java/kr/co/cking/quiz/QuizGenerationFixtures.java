package kr.co.cking.quiz;

import kr.co.cking.quiz.generation.GeneratedQuestion;
import kr.co.cking.quiz.generation.GeneratedQuiz;
import kr.co.cking.quiz.generation.QuizPromptVersion;

import java.util.ArrayList;
import java.util.List;

/** 영상 Quiz 생성 테스트 데이터. 기존 Validator를 통과하는 보기 4개짜리 문항을 3개 또는 5개 만든다. */
public final class QuizGenerationFixtures {

    public static final String VIDEO_ID = "dQw4w9WgXcQ";
    public static final String CONTENT_TEXT = "물은 증발해 구름이 된다. 구름은 비가 되어 내린다. 비는 강으로 흐른다. "
            + "강물은 바다로 간다. 바닷물도 다시 증발한다.";

    private static final String[] EVIDENCES = {
            "물은 증발해 구름이 된다.", "구름은 비가 되어 내린다.", "비는 강으로 흐른다.", "강물은 바다로 간다.",
            "바닷물도 다시 증발한다."};

    private QuizGenerationFixtures() {
    }

    public static GeneratedQuiz generated(int questionCount) {
        List<GeneratedQuestion> questions = new ArrayList<>();
        for (int index = 0; index < questionCount; index++) {
            questions.add(new GeneratedQuestion(
                    "물의 순환 질문 " + (index + 1),
                    List.of("보기 가" + index, "보기 나" + index, "보기 다" + index, "보기 라" + index),
                    index % 4,
                    "해설 " + (index + 1),
                    EVIDENCES[index]));
        }
        return new GeneratedQuiz(questions, QuizPromptVersion.V1.value());
    }
}
