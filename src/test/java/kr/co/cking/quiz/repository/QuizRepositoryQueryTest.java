package kr.co.cking.quiz.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** DB 없이 공개 목록 조회가 원본 텍스트를 읽지 않는 JPQL로 선언돼 있는지 확인한다. */
class QuizRepositoryQueryTest {

    @Test
    void 공개_목록은_필요한_컬럼만_조회하고_원본_텍스트를_읽지_않는다() throws NoSuchMethodException {
        Query query = QuizRepository.class
                .getMethod("findPublishedByCreatorId", Long.class, Pageable.class)
                .getAnnotation(Query.class);
        String select = query.value().replaceAll("\\s+", " ").trim();

        assertThat(select).startsWith("select new kr.co.cking.quiz.repository.PublishedQuizSummary(");
        assertThat(select).doesNotContainIgnoringCase("contentText");
        assertThat(select).contains("order by q.publishedAt desc, q.quizId desc");
        assertThat(query.countQuery()).contains("count(q)");
        assertThat(Arrays.stream(PublishedQuizSummary.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("quizId", "publishedRevisionId", "publishedAt");
    }
}
