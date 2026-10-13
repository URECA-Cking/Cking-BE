package kr.co.cking.interest.repository;

import java.util.List;
import kr.co.cking.interest.domain.InterestSubtopic;
import kr.co.cking.interest.domain.InterestSubtopicId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InterestSubtopicRepository extends JpaRepository<InterestSubtopic, InterestSubtopicId> {

    @Query("select s from InterestSubtopic s where s.id.subtopicVersion = :version and s.active = true "
            + "order by s.displayOrder")
    List<InterestSubtopic> findActiveBySubtopicVersion(@Param("version") String version);

    /** 해시 계산 대상이다. 활성 여부와 무관하게 세트의 모든 행을 노출 순서대로 읽는다. */
    @Query("select s from InterestSubtopic s where s.id.subtopicVersion = :version order by s.displayOrder")
    List<InterestSubtopic> findAllBySubtopicVersion(@Param("version") String version);
}
