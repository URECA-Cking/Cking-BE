package kr.co.cking.quiz.repository;
import kr.co.cking.quiz.domain.QuizAttempt;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
public interface QuizAttemptRepository extends JpaRepository<QuizAttempt, Long> {
    Optional<QuizAttempt> findByMemberIdAndRequestId(Long memberId, String requestId);
    Optional<QuizAttempt> findByMemberIdAndQuizId(Long memberId, Long quizId);
    /** Quiz 보상 재전달 후보(양수 점수)를 PK 순서로 제한된 수만큼 읽는다(이슈 #543). */
    List<QuizAttempt> findByAttemptIdGreaterThanAndCorrectCountGreaterThanOrderByAttemptIdAsc(
            Long attemptId, int correctCount, Pageable pageable);
}
