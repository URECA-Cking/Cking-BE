package kr.co.cking.quiz.repository;
import kr.co.cking.quiz.domain.QuizAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface QuizAttemptRepository extends JpaRepository<QuizAttempt, Long> {
    Optional<QuizAttempt> findByMemberIdAndRequestId(Long memberId, String requestId);
    Optional<QuizAttempt> findByMemberIdAndQuizId(Long memberId, Long quizId);
}
