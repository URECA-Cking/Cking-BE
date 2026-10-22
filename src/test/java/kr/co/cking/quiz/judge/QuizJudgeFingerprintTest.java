package kr.co.cking.quiz.judge;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QuizJudgeFingerprintTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final QuizJudgeResponseParser parser = new QuizJudgeResponseParser(objectMapper);
    private final QuizJudgeEvaluationValidator validator = new QuizJudgeEvaluationValidator();

    @Test
    void 입력_해시는_길이를_앞에_붙인_값을_순서대로_이은_SHA_256이다() throws Exception {
        QuizJudgeInput input = new QuizJudgeInput("본문", List.of(
                new QuizJudgeInput.Question(0, "질문", List.of("가", "나", "다", "라"), 1, "해설", "근거")));
        String expected = "19:quiz-judge-input-v1" + "6:본문" + "1:1" + "1:0" + "6:질문" + "1:4"
                + "3:가" + "3:나" + "3:다" + "3:라" + "1:1" + "6:해설" + "6:근거";

        assertThat(QuizJudgeFingerprint.inputSha256(input)).isEqualTo(sha256(expected));
    }

    @Test
    void 입력의_어느_값이_바뀌어도_입력_해시가_바뀐다() {
        QuizJudgeInput base = QuizJudgeFixtures.input(3);
        String hash = QuizJudgeFingerprint.inputSha256(base);
        QuizJudgeInput.Question first = base.questions().get(0);
        List<QuizJudgeInput> changed = List.of(
                new QuizJudgeInput(base.contentText() + " ", base.questions()),
                replaceFirst(base, new QuizJudgeInput.Question(0, first.question() + "?", first.options(),
                        first.correctOptionIndex(), first.explanation(), first.sourceEvidence())),
                replaceFirst(base, new QuizJudgeInput.Question(0, first.question(),
                        List.of(first.options().get(1), first.options().get(0), first.options().get(2),
                                first.options().get(3)), first.correctOptionIndex(), first.explanation(),
                        first.sourceEvidence())),
                replaceFirst(base, new QuizJudgeInput.Question(0, first.question(), first.options(), 3,
                        first.explanation(), first.sourceEvidence())),
                replaceFirst(base, new QuizJudgeInput.Question(0, first.question(), first.options(),
                        first.correctOptionIndex(), first.explanation() + ".", first.sourceEvidence())),
                replaceFirst(base, new QuizJudgeInput.Question(0, first.question(), first.options(),
                        first.correctOptionIndex(), first.explanation(), first.sourceEvidence() + ".")));

        assertThat(QuizJudgeFingerprint.inputSha256(QuizJudgeFixtures.input(3))).isEqualTo(hash);
        changed.forEach(input -> assertThat(QuizJudgeFingerprint.inputSha256(input)).isNotEqualTo(hash));
    }

    @Test
    void 설정_fingerprint는_모델_token_한도_reason_한도_지시문_schema에_따라_바뀐다() {
        QuizJudgeSettings base = QuizJudgeSettings.current("model-a", "medium", 8192, 200, objectMapper);
        String fingerprint = QuizJudgeFingerprint.settingsFingerprint(base);

        assertThat(base.promptVersion()).isEqualTo(QuizJudgePrompt.PROMPT_VERSION);
        assertThat(base.policyVersion()).isEqualTo(QuizJudgePrompt.POLICY_VERSION);
        assertThat(QuizJudgeFingerprint.settingsFingerprint(
                QuizJudgeSettings.current("model-a", "medium", 8192, 200, objectMapper))).isEqualTo(fingerprint);
        for (QuizJudgeSettings changed : List.of(
                QuizJudgeSettings.current("model-b", "medium", 8192, 200, objectMapper),
                QuizJudgeSettings.current("model-a", "high", 8192, 200, objectMapper),
                QuizJudgeSettings.current("model-a", "medium", 12288, 200, objectMapper),
                QuizJudgeSettings.current("model-a", "medium", 8192, 150, objectMapper))) {
            assertThat(QuizJudgeFingerprint.settingsFingerprint(changed)).isNotEqualTo(fingerprint);
        }
        assertThat(QuizJudgeSettings.current("model-a", "medium", 8192, 150, objectMapper).instructionsSha256())
                .isNotEqualTo(base.instructionsSha256());
        assertThat(base.schemaSha256()).isEqualTo(
                QuizJudgeFingerprint.sha256Hex(objectMapper.writeValueAsString(QuizJudgePrompt.responseSchema())));
    }

    @Test
    void 결과_해시는_응답의_문항_순서와_무관하고_판정이나_근거가_바뀌면_달라진다() {
        String ordered = QuizJudgeFixtures.allPass(3);
        String reversed = QuizJudgeFixtures.json(Map.of("questions", List.of(
                QuizJudgeFixtures.question(2, (no, rubric) -> new String[] {"pass", "근거가 원문과 일치합니다."}),
                QuizJudgeFixtures.question(1, (no, rubric) -> new String[] {"pass", "근거가 원문과 일치합니다."}),
                QuizJudgeFixtures.question(0, (no, rubric) -> new String[] {"pass", "근거가 원문과 일치합니다."}))));
        String failed = QuizJudgeFixtures.output(3, (no, rubric) -> no == 2 && rubric == JudgeRubric.UNIQUE_ANSWER
                ? new String[] {"fail", "근거가 원문과 일치합니다."} : new String[] {"pass", "근거가 원문과 일치합니다."});
        String reasonChanged = QuizJudgeFixtures.output(3, (no, rubric) -> no == 2 && rubric == JudgeRubric.UNIQUE_ANSWER
                ? new String[] {"pass", "근거가 원문과 일치합니다!"} : new String[] {"pass", "근거가 원문과 일치합니다."});

        String hash = resultSha256(ordered, "input", "settings", QuizJudgePrompt.POLICY_VERSION);

        assertThat(resultSha256(reversed, "input", "settings", QuizJudgePrompt.POLICY_VERSION)).isEqualTo(hash);
        assertThat(resultSha256(failed, "input", "settings", QuizJudgePrompt.POLICY_VERSION)).isNotEqualTo(hash);
        assertThat(resultSha256(reasonChanged, "input", "settings", QuizJudgePrompt.POLICY_VERSION))
                .isNotEqualTo(hash);
        assertThat(resultSha256(ordered, "other", "settings", QuizJudgePrompt.POLICY_VERSION)).isNotEqualTo(hash);
        assertThat(resultSha256(ordered, "input", "other", QuizJudgePrompt.POLICY_VERSION)).isNotEqualTo(hash);
        assertThat(resultSha256(ordered, "input", "settings", "quiz-judge-policy-v2")).isNotEqualTo(hash);
    }

    private String resultSha256(String json, String inputSha256, String settingsFingerprint, String policyVersion) {
        return QuizJudgeFingerprint.resultSha256(inputSha256, settingsFingerprint, policyVersion,
                validator.validate(parser.parse(json), 3, 200));
    }

    private QuizJudgeInput replaceFirst(QuizJudgeInput input, QuizJudgeInput.Question first) {
        return new QuizJudgeInput(input.contentText(), List.of(first, input.questions().get(1), input.questions().get(2)));
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
