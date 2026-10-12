package kr.co.cking.quiz.judge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Judge 입력·설정·결과의 canonical SHA-256(소문자 hex).
 *
 * <p>해시 입력은 값을 순서대로 이어 붙인 UTF-8 바이트이며, 각 값은 {@code <UTF-8 바이트 길이>:<값>}으로 적어 경계가
 * 모호하지 않게 한다({@code QuizRevisionFingerprint}와 같은 표기). 정수는 10진수 문자열, 열거값은 schema 값이다.
 * 문자열은 정규화하지 않는다. 첫 값은 해시 종류별 버전이라 서로 다른 종류의 해시가 섞이지 않는다.
 * <ul>
 *   <li>입력({@link #INPUT_VERSION}): contentText, 문항 수, 문항마다 questionNo·질문·보기 수·보기들·정답 번호·해설·
 *       sourceEvidence</li>
 *   <li>설정({@link #SETTINGS_VERSION}): {@link QuizJudgeSettings}의 필드를 선언 순서대로</li>
 *   <li>결과({@link #RESULT_VERSION}): 입력 해시, 설정 fingerprint, 요약 정책 버전, 판정 요약, 문항 수, 문항마다
 *       questionNo와 {@link JudgeRubric} 선언 순서대로 항목 이름·verdict·reason</li>
 * </ul>
 * 규칙을 바꾸면 해당 버전을 올리고 기존 값은 기존 규칙으로 검증한다.
 */
public final class QuizJudgeFingerprint {

    public static final String INPUT_VERSION = "quiz-judge-input-v1";
    public static final String SETTINGS_VERSION = "quiz-judge-settings-v1";
    public static final String RESULT_VERSION = "quiz-judge-result-v1";

    private QuizJudgeFingerprint() {
    }

    public static String inputSha256(QuizJudgeInput input) {
        StringBuilder value = new StringBuilder();
        append(value, INPUT_VERSION);
        append(value, input.contentText());
        append(value, input.questions().size());
        for (QuizJudgeInput.Question question : input.questions()) {
            append(value, question.questionNo());
            append(value, question.question());
            append(value, question.options().size());
            question.options().forEach(option -> append(value, option));
            append(value, question.correctOptionIndex());
            append(value, question.explanation());
            append(value, question.sourceEvidence());
        }
        return sha256Hex(value.toString());
    }

    public static String settingsFingerprint(QuizJudgeSettings settings) {
        StringBuilder value = new StringBuilder();
        append(value, SETTINGS_VERSION);
        append(value, settings.model());
        append(value, settings.reasoningEffort());
        append(value, settings.maxOutputTokens());
        append(value, settings.reasonMaxLength());
        append(value, settings.promptVersion());
        append(value, settings.rubricVersion());
        append(value, settings.schemaVersion());
        append(value, settings.policyVersion());
        append(value, settings.instructionsSha256());
        append(value, settings.schemaSha256());
        return sha256Hex(value.toString());
    }

    public static String resultSha256(String inputSha256, String settingsFingerprint, String policyVersion,
                                      QuizJudgeEvaluation evaluation) {
        StringBuilder value = new StringBuilder();
        append(value, RESULT_VERSION);
        append(value, inputSha256);
        append(value, settingsFingerprint);
        append(value, policyVersion);
        append(value, evaluation.aggregation().name());
        append(value, evaluation.questions().size());
        for (QuizJudgeEvaluation.QuestionEvaluation question : evaluation.questions()) {
            append(value, question.questionNo());
            for (JudgeRubric rubric : JudgeRubric.values()) {
                QuizJudgeEvaluation.RubricResult result = question.item(rubric);
                append(value, rubric.field());
                append(value, result.verdict().value());
                append(value, result.reason());
            }
        }
        return sha256Hex(value.toString());
    }

    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    private static void append(StringBuilder target, int value) {
        append(target, String.valueOf(value));
    }

    private static void append(StringBuilder target, String value) {
        Objects.requireNonNull(value, "해시 대상 값은 null일 수 없습니다.");
        target.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
    }
}
