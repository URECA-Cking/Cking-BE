package kr.co.cking.interest.repository;

import java.util.List;
import kr.co.cking.interest.domain.MemberInterestSubtopic;
import kr.co.cking.interest.domain.MemberInterestSubtopicId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberInterestSubtopicRepository
        extends JpaRepository<MemberInterestSubtopic, MemberInterestSubtopicId> {

    /** 회원의 세부 선택을 세트의 노출 순서대로 읽는다. */
    @Query("select ms from MemberInterestSubtopic ms, InterestSubtopic s "
            + "where ms.id.memberId = :memberId "
            + "and s.id.subtopicVersion = ms.id.subtopicVersion and s.id.subtopicCode = ms.id.subtopicCode "
            + "order by s.displayOrder")
    List<MemberInterestSubtopic> findByMemberIdOrderByDisplayOrder(@Param("memberId") Long memberId);
}
