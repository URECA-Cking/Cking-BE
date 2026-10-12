package kr.co.cking.quiz.repository;

import kr.co.cking.quiz.domain.QuizOption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface QuizOptionRepository extends JpaRepository<QuizOption, Long> {

    List<QuizOption> findByQuestionIdInOrderByQuestionIdAscOptionNoAsc(Collection<Long> questionIds);
}
