package kr.co.cking.quiz.application.dto;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** 저장 문자열은 strip, 중복 비교만 NFC·공백 정규화한다. 순서와 선택/수정 형태는 fingerprint에 포함한다. */
public record QuizCompositionCommand(UUID requestId, Long baseRevisionId, String baseRevisionSha256,
                                     List<Selection> questions) {
    public QuizCompositionCommand {
        if (requestId == null || baseRevisionId == null || baseRevisionId <= 0 || baseRevisionSha256 == null
                || !baseRevisionSha256.matches("[0-9a-f]{64}") || questions == null || questions.isEmpty()
                || questions.size() > 5 || questions.stream().anyMatch(q -> q == null)) throw invalid();
        questions = List.copyOf(questions);
        var ids = new HashSet<Long>();
        for (var q : questions) if (q.sourceQuestionId() != null && !ids.add(q.sourceQuestionId())) throw invalid();
    }
    public record Selection(Long sourceQuestionId, Content content) {
        public Selection {
            if ((sourceQuestionId == null && content == null) || (sourceQuestionId != null && sourceQuestionId <= 0)) throw invalid();
        }
    }
    public record Content(String question, List<String> options, Integer correctOptionIndex, String explanation,
                          String sourceEvidence) {
        public Content {
            question = text(question, 500);
            explanation = text(explanation, 1000);
            sourceEvidence = sourceEvidence == null || sourceEvidence.isBlank() ? "" : sourceEvidence.strip();
            if (sourceEvidence.length() > 1000 || options == null || options.size() != 4
                    || correctOptionIndex == null || correctOptionIndex < 0 || correctOptionIndex > 3) throw invalid();
            options = options.stream().map(o -> text(o, 200)).toList();
            if (options.stream().map(QuizCompositionCommand::duplicateKey).distinct().count() != 4) throw invalid();
        }
    }
    public String fingerprint() {
        var b = new StringBuilder();
        append(b, "quiz-composition-request-v1"); append(b, baseRevisionId); append(b, baseRevisionSha256);
        append(b, questions.size());
        for (var q : questions) {
            append(b, q.sourceQuestionId()); append(b, q.content() == null ? "COPY" : "CONTENT");
            if (q.content() != null) {
                var c = q.content(); append(b, c.question());
                c.options().forEach(o -> append(b, o)); append(b, c.correctOptionIndex());
                append(b, c.explanation()); append(b, c.sourceEvidence());
            }
        }
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String duplicateKey(String text) { return Normalizer.normalize(text.strip(), Normalizer.Form.NFC).replaceAll("\\s+", " "); }
    private static String text(String value, int max) {
        if (value == null || value.isBlank() || value.strip().length() > max) throw invalid();
        return value.strip();
    }
    private static void append(StringBuilder b, Object value) {
        if (value == null) { b.append("-1:"); return; }
        var s = value.toString(); b.append(s.getBytes(StandardCharsets.UTF_8).length).append(':').append(s);
    }
    private static BusinessException invalid() { return new BusinessException(CommonErrorCode.VALIDATION_FAILED); }
}
