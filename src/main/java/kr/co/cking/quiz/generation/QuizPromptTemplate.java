package kr.co.cking.quiz.generation;

/** Provider에 전달할 버전별 객관식 퀴즈 프롬프트다. */
public final class QuizPromptTemplate {

    private static final String V1_TEMPLATE = """
            You generate grounded multiple-choice quizzes from the supplied content.
            Prompt version: %s
            Generate exactly %d question(s), each with exactly %d distinct option(s).
            correctOptionIndex must be a zero-based integer.
            Every sourceEvidence must be an exact contiguous excerpt from the content.
            Return JSON only with this shape:
            {"questions":[{"question":"...","options":["..."],"correctOptionIndex":0,"explanation":"...","sourceEvidence":"..."}],"promptVersion":"%s"}

            Content:
            %s
            """;

    private final QuizPromptVersion version;

    private QuizPromptTemplate(QuizPromptVersion version) {
        this.version = version;
    }

    public static QuizPromptTemplate forVersion(QuizPromptVersion version) {
        if (version == null) {
            throw new IllegalArgumentException("프롬프트 버전은 필수입니다.");
        }
        return new QuizPromptTemplate(version);
    }

    public String render(QuizGenerationInput input) {
        if (input == null) {
            throw new IllegalArgumentException("생성 입력은 필수입니다.");
        }
        return switch (version) {
            case V1 -> V1_TEMPLATE.formatted(
                    version.value(), input.questionCount(), input.optionCount(), version.value(), input.contentText());
        };
    }

    public QuizPromptVersion version() {
        return version;
    }
}
