package kr.co.cking.quiz.application.dto;

import java.time.Instant;
import java.util.List;

/**
 * 게시된 revision의 공개 조회 결과. 정답 index·해설·근거·원본 텍스트·promptVersion·해시·검토 정보는 필드 자체를
 * 두지 않는다.
 */
public final class PublicQuizViews {

    private PublicQuizViews() {
    }

    public record Summary(Long quizId, Long revisionId, String title, int questionCount, Instant publishedAt) {
    }

    public record Detail(
            Long quizId,
            Long creatorId,
            Long revisionId,
            String videoId,
            String title,
            Instant publishedAt,
            List<Question> questions
    ) {
    }

    /** @param questionNo 0-based 문항 순서. {@code options}는 표시 순서대로다 */
    public record Question(int questionNo, String question, List<String> options) {
    }
}
