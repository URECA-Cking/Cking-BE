package kr.co.cking.quiz.application;

import kr.co.cking.quiz.application.dto.QuizAttemptCommand;
import kr.co.cking.quiz.application.dto.QuizAttemptResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** UTF-8 길이 접두어로 경계를 고정한 버전별 SHA-256. requestId는 내용 fingerprint에서 제외한다. */
final class QuizAttemptHashes {
    private QuizAttemptHashes() {}
    static String request(QuizAttemptCommand c) {
        StringBuilder b = new StringBuilder();
        add(b, "quiz-attempt-request-v1", c.memberId(), c.creatorId(), c.quizId(), c.revisionId(), c.answers().size());
        c.answers().forEach(a -> add(b, a.questionNo(), a.selectedOptionIndex()));
        return sha(b);
    }
    static String snapshot(Long memberId, String requestFingerprint, QuizAttemptResult r) {
        StringBuilder b = new StringBuilder();
        add(b, "quiz-attempt-snapshot-v1", memberId, requestFingerprint, r.requestId(), r.creatorId(), r.quizId(),
                r.revisionId(), r.title(), r.videoId(), r.questionCount(), r.correctCount(), r.submittedAt(),
                r.revisionSha256(), r.reward().amount(), r.reward().requestId(), r.reward().status());
        for (var q : r.questions()) {
            add(b, q.questionNo(), q.question(), q.options().size());
            q.options().forEach(o -> add(b, o));
            add(b, q.selectedOptionIndex(), q.correctOptionIndex(), q.correct(), q.explanation(), q.sourceEvidence());
        }
        return sha(b);
    }
    private static void add(StringBuilder b, Object... values) {
        for (Object value : values) {
            if (value == null) { b.append("-1:"); continue; }
            String s = value.toString(); b.append(s.getBytes(StandardCharsets.UTF_8).length).append(':').append(s);
        }
    }
    private static String sha(StringBuilder b) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e); }
    }
}
