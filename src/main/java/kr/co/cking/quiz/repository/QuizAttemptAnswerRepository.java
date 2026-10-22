package kr.co.cking.quiz.repository;
import kr.co.cking.quiz.domain.QuizAttemptAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface QuizAttemptAnswerRepository extends JpaRepository<QuizAttemptAnswer, Long> {
    List<QuizAttemptAnswer> findByAttemptIdOrderByQuestionNoAsc(Long attemptId);
}
