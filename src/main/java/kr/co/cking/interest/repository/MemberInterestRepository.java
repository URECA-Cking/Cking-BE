package kr.co.cking.interest.repository;

import java.util.List;
import kr.co.cking.interest.domain.MemberInterest;
import kr.co.cking.interest.domain.MemberInterestId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberInterestRepository extends JpaRepository<MemberInterest, MemberInterestId> {

    /** 회원의 선택을 분류체계의 노출 순서대로 읽는다. */
    @Query("select mi from MemberInterest mi, InterestCategory c "
            + "where mi.id.memberId = :memberId "
            + "and c.id.taxonomyVersion = mi.id.taxonomyVersion and c.id.interestCode = mi.id.interestCode "
            + "order by c.displayOrder")
    List<MemberInterest> findByMemberIdOrderByDisplayOrder(@Param("memberId") Long memberId);

    /** 현재 활성 분류체계의 활성 분야로 한정한 회원의 선택 코드다. */
    @Query("select mi.id.interestCode from MemberInterest mi, InterestTaxonomy t, InterestCategory c "
            + "where mi.id.memberId = :memberId "
            + "and t.taxonomyVersion = mi.id.taxonomyVersion and t.active = true "
            + "and c.id.taxonomyVersion = mi.id.taxonomyVersion and c.id.interestCode = mi.id.interestCode and c.active = true")
    List<String> findSelectableInterestCodes(@Param("memberId") Long memberId);
}
