package kr.co.cking.quiz.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Quiz 원본 텍스트와 revision 내용의 SHA-256(소문자 hex)을 계산한다.
 *
 * <p>revision 해시 입력은 아래 값을 순서대로 이어 붙인 UTF-8 바이트다. 각 값은 {@code <UTF-8 바이트 길이>:<값>}으로
 * 적어 값 경계가 모호하지 않게 한다. 정수는 10진수 문자열이다. 문자열은 정규화하지 않고 저장된 그대로 쓴다.
 * <ol>
 *   <li>{@link #VERSION}</li>
 *   <li>Quiz 원본 텍스트의 SHA-256</li>
 *   <li>제목, promptVersion, 문항 수</li>
 *   <li>문항마다 순서대로: 질문, 보기 수, 보기(순서대로), 0-based 정답 index, 해설, sourceEvidence</li>
 * </ol>
 * 규칙을 바꾸면 {@link #VERSION}을 올리고 기존 revision은 기존 규칙으로 검증한다.
 *
 * <p>Creator가 구성한 revision은 {@link #VERSION_V2}다. 같은 길이 접두어 방식으로 아래 값을 이어 붙인다.
 * <ol>
 *   <li>{@link #VERSION_V2}</li>
 *   <li>Quiz 원본 텍스트의 SHA-256, 제목, promptVersion</li>
 *   <li>부모 revision ID, 부모 revision 해시, 문항 수</li>
 *   <li>문항마다 순서대로: 변경 유형, 원본 문항 ID, 직전 문항 ID(없으면 빈 문자열), 질문, 보기 수, 보기(순서대로),
 *       0-based 정답 index, 해설, sourceEvidence</li>
 * </ol>
 * 문항 선택·순서·내용·정답·근거·계보 중 하나라도 바뀌면 해시가 바뀐다.
 */
public final class QuizRevisionFingerprint {

    public static final String VERSION = "quiz-revision-v1";
    public static final String VERSION_V2 = "quiz-revision-v2";

    private QuizRevisionFingerprint() {
    }

    /** 해시 대상 문항 내용. */
    public record Question(
            String question,
            List<String> options,
            int correctOptionIndex,
            String explanation,
            String sourceEvidence
    ) {
    }

    /** v2 해시 대상 문항. 원본·직전 문항 ID는 없으면 null이다. */
    public record ComposedQuestion(
            QuizQuestionChangeType changeType,
            Long originQuestionId,
            Long previousQuestionId,
            Question content
    ) {
    }

    public static String contentTextSha256(String contentText) {
        return sha256Hex(Objects.requireNonNull(contentText, "contentText").getBytes(StandardCharsets.UTF_8));
    }

    public static String revisionSha256(
            String contentTextSha256, String title, String promptVersion, List<Question> questions
    ) {
        StringBuilder input = new StringBuilder();
        append(input, VERSION);
        append(input, contentTextSha256);
        append(input, title);
        append(input, promptVersion);
        append(input, String.valueOf(questions.size()));
        for (Question question : questions) {
            append(input, question.question());
            append(input, String.valueOf(question.options().size()));
            for (String option : question.options()) {
                append(input, option);
            }
            append(input, String.valueOf(question.correctOptionIndex()));
            append(input, question.explanation());
            append(input, question.sourceEvidence());
        }
        return sha256Hex(input.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static String revisionSha256V2(
            String contentTextSha256, String title, String promptVersion, Long parentRevisionId,
            String parentRevisionSha256, List<ComposedQuestion> questions
    ) {
        StringBuilder input = new StringBuilder();
        append(input, VERSION_V2);
        append(input, contentTextSha256);
        append(input, title);
        append(input, promptVersion);
        append(input, String.valueOf(Objects.requireNonNull(parentRevisionId, "parentRevisionId")));
        append(input, parentRevisionSha256);
        append(input, String.valueOf(questions.size()));
        for (ComposedQuestion composed : questions) {
            Question question = composed.content();
            append(input, Objects.requireNonNull(composed.changeType(), "changeType").name());
            append(input, composed.originQuestionId() == null ? "" : String.valueOf(composed.originQuestionId()));
            append(input, composed.previousQuestionId() == null ? "" : String.valueOf(composed.previousQuestionId()));
            append(input, question.question());
            append(input, String.valueOf(question.options().size()));
            for (String option : question.options()) {
                append(input, option);
            }
            append(input, String.valueOf(question.correctOptionIndex()));
            append(input, question.explanation());
            append(input, question.sourceEvidence());
        }
        return sha256Hex(input.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void append(StringBuilder input, String value) {
        Objects.requireNonNull(value, "해시 대상 값은 null일 수 없습니다.");
        input.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }
}
