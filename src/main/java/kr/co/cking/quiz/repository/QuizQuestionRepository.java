package kr.co.cking.quiz.repository;

import kr.co.cking.quiz.domain.QuizQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QuizQuestionRepository extends JpaRepository<QuizQuestion, Long> {

    List<QuizQuestion> findByRevisionIdOrderByQuestionNoAsc(Long revisionId);
}
