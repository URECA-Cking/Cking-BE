package kr.co.cking.quiz.presentation.dto;

import kr.co.cking.quiz.application.dto.PublicQuizViews;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 로그인 없이 조회하는 공개 Quiz 응답. 정답 index·해설·근거·원본 텍스트·promptVersion·해시·검토 정보는 필드로
 * 정의하지 않는다.
 */
public final class PublicQuizResponse {

    private PublicQuizResponse() {
    }

    public record Summary(Long quizId, Long revisionId, String title, int questionCount, Instant publishedAt) {

        public static Summary from(PublicQuizViews.Summary view) {
            return new Summary(view.quizId(), view.revisionId(), view.title(), view.questionCount(), view.publishedAt());
        }
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

        public static Detail from(PublicQuizViews.Detail view) {
            return new Detail(view.quizId(), view.creatorId(), view.revisionId(), view.videoId(), view.title(),
                    view.publishedAt(), view.questions().stream().map(Question::from).toList());
        }
    }

    public record Question(int questionNo, String question, List<Option> options) {

        static Question from(PublicQuizViews.Question view) {
            List<String> options = view.options();
            return new Question(view.questionNo(), view.question(), IntStream.range(0, options.size())
                    .mapToObj(index -> new Option(index, options.get(index)))
                    .toList());
        }
    }

    /** @param optionNo 0-based 표시 순서 */
    public record Option(int optionNo, String text) {
    }
}
