package kr.co.cking.quiz.repository;
import kr.co.cking.quiz.domain.QuizCompositionRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface QuizCompositionRequestRepository extends JpaRepository<QuizCompositionRequest, Long> {
    Optional<QuizCompositionRequest> findByQuizIdAndRequestId(Long quizId, String requestId);
}
