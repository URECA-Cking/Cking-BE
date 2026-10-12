package kr.co.cking.quiz.repository;

import kr.co.cking.quiz.domain.QuizJudgeStoredResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface QuizJudgeStoredResultRepository extends JpaRepository<QuizJudgeStoredResult, Long> {

    boolean existsBySeriesId(Long seriesId);

    Optional<QuizJudgeStoredResult> findByJobId(Long jobId);

    Optional<QuizJudgeStoredResult> findByRevisionId(Long revisionId);
}
