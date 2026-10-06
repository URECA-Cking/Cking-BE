package kr.co.cking.interest.repository;

import java.util.List;
import kr.co.cking.interest.domain.InterestCategoryId;
import kr.co.cking.interest.domain.InterestRecommendationState;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** 개인화 조회용 읽기 전용 쿼리다. 회원의 선택과 같은 분류체계 버전의 활성 세대만 연결한다. */
public interface InterestRecommendationQueryRepository extends Repository<InterestRecommendationState, InterestCategoryId> {

    /**
     * 회원이 고른 분야의 현재 활성 세대 후보 중 Creator Space가 있어 카드를 만들 수 있는 후보를 한 번에 읽는다.
     * 활성 세대가 없거나 빈 세대인 분야는 행이 나오지 않는다.
     */
    @Query("""
            select new kr.co.cking.interest.repository.ActiveInterestRecommendationCandidate(
                mi.id.interestCode, c.creatorId, c.rank
            )
            from MemberInterest mi, InterestRecommendationState s,
                 InterestRecommendationCandidate c, CreatorSpace space
            where mi.id.memberId = :memberId
              and s.id.taxonomyVersion = mi.id.taxonomyVersion
              and s.id.interestCode = mi.id.interestCode
              and c.generationId = s.currentGenerationId
              and space.creatorId = c.creatorId
            order by mi.id.interestCode asc, c.rank asc, c.creatorId asc
            """)
    List<ActiveInterestRecommendationCandidate> findActiveCandidatesByMemberId(@Param("memberId") Long memberId);
}
