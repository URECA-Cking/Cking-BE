package kr.co.cking.quiz.repository;

import kr.co.cking.quiz.domain.QuizRevision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface QuizRevisionRepository extends JpaRepository<QuizRevision, Long> {

    List<QuizRevision> findByQuizIdOrderByRevisionNoAsc(Long quizId);

    Optional<QuizRevision> findTopByQuizIdOrderByRevisionNoDesc(Long quizId);

    List<QuizRevision> findByQuizIdIn(Collection<Long> quizIds);
}
