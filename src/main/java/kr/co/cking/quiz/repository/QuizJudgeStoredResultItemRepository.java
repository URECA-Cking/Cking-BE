package kr.co.cking.quiz.repository;

import kr.co.cking.quiz.domain.QuizJudgeStoredResultItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuizJudgeStoredResultItemRepository extends JpaRepository<QuizJudgeStoredResultItem, Long> {
    java.util.List<QuizJudgeStoredResultItem> findByResultIdOrderByQuestionNoAscItemIdAsc(Long resultId);
}
