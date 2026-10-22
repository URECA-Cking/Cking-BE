package kr.co.cking.interest.application;

import java.util.List;
import kr.co.cking.interest.repository.ActiveInterestRecommendationCandidate;
import kr.co.cking.interest.repository.InterestRecommendationQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 개인화 추천이 읽는 관심 분야 후보 조회다. 모델 API를 호출하지 않고 저장된 활성 세대만 읽는다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InterestRecommendationQueryService {

    private final InterestRecommendationQueryRepository queryRepository;

    /** 회원이 고른 관심 분야 전체의 현재 활성 후보를 분야 코드·원래 rank 순으로 반환한다. 선택이 없으면 빈 목록이다. */
    public List<ActiveInterestRecommendationCandidate> findActiveCandidates(Long memberId) {
        return queryRepository.findActiveCandidatesByMemberId(memberId);
    }
}
