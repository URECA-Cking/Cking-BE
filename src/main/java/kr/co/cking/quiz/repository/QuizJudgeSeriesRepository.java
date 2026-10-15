package kr.co.cking.quiz.repository;

import jakarta.persistence.LockModeType;
import kr.co.cking.quiz.domain.QuizJudgeSeries;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface QuizJudgeSeriesRepository extends JpaRepository<QuizJudgeSeries, Long> {

    /** 시도 번호 할당과 결과 저장을 직렬화한다. Quiz 잠금 다음에 잡는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from QuizJudgeSeries s where s.revisionId = :revisionId")
    Optional<QuizJudgeSeries> findByRevisionIdForUpdate(@Param("revisionId") Long revisionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from QuizJudgeSeries s where s.seriesId = :seriesId")
    Optional<QuizJudgeSeries> findByIdForUpdate(@Param("seriesId") Long seriesId);
}
