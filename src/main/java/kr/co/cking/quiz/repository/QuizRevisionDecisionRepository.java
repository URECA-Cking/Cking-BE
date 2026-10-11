package kr.co.cking.quiz.repository;

import kr.co.cking.quiz.domain.QuizRevisionDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface QuizRevisionDecisionRepository extends JpaRepository<QuizRevisionDecision, Long> {

    Optional<QuizRevisionDecision> findByRevisionId(Long revisionId);

    List<QuizRevisionDecision> findByRevisionIdIn(Collection<Long> revisionIds);
}
