package kr.co.cking.quiz.judge;

import java.util.List;

/**
 * Judge 입력. 호출자는 저장소에서 무결성 검증을 마친 Quiz revision으로만 만든다.
 *
 * <p>문항은 0부터 이어지는 {@code questionNo} 순서이고, 보기는 표시 순서 그대로 4개이며, 정답은 0-based 보기
 * 번호다. 문자열은 정규화하지 않고 저장된 그대로 쓴다. 규칙을 어기면 호출 오류이므로 Provider를 호출하지 않는다.
 */
public record QuizJudgeInput(String contentText, List<Question> questions) {

    public static final int OPTION_COUNT = 4;

    public QuizJudgeInput {
        requireText(contentText, "contentText");
        if (questions == null || questions.isEmpty()) {
            throw new IllegalArgumentException("Judge 입력 문항이 없습니다.");
        }
        questions = List.copyOf(questions);
        for (int index = 0; index < questions.size(); index++) {
            if (questions.get(index).questionNo() != index) {
                throw new IllegalArgumentException("Judge 입력 문항 번호는 0부터 순서대로 이어져야 합니다.");
            }
        }
    }

    public record Question(
            int questionNo,
            String question,
            List<String> options,
            int correctOptionIndex,
            String explanation,
            String sourceEvidence
    ) {

        public Question {
            requireText(question, "question");
            if (options == null || options.size() != OPTION_COUNT) {
                throw new IllegalArgumentException("Judge 입력 보기는 " + OPTION_COUNT + "개여야 합니다.");
            }
            options = List.copyOf(options);
            options.forEach(option -> requireText(option, "option"));
            if (correctOptionIndex < 0 || correctOptionIndex >= OPTION_COUNT) {
                throw new IllegalArgumentException("Judge 입력 정답 번호가 보기 범위를 벗어났습니다.");
            }
            requireText(explanation, "explanation");
            requireText(sourceEvidence, "sourceEvidence");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Judge 입력 " + field + "이(가) 비어 있습니다.");
        }
    }
}
