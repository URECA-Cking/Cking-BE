package kr.co.cking.interest.application;

import java.util.List;
import kr.co.cking.interest.application.dto.SelectableInterests;
import kr.co.cking.interest.domain.InterestPolicy;
import kr.co.cking.interest.repository.InterestCategoryRepository;
import kr.co.cking.interest.repository.InterestTaxonomyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관심 분야 분류체계 조회다. 요청 중 외부 모델을 호출하지 않는다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InterestQueryService {

    private final InterestTaxonomyRepository taxonomyRepository;
    private final InterestCategoryRepository categoryRepository;

    /** 활성 분류체계의 활성 분야를 노출 순서대로 반환한다. 활성 분류체계가 없으면 빈 목록이다. */
    public SelectableInterests findSelectable() {
        return taxonomyRepository.findByActiveTrue()
                .map(taxonomy -> new SelectableInterests(
                        taxonomy.getTaxonomyVersion(),
                        InterestPolicy.MAX_SELECTION,
                        categoryRepository.findActiveByTaxonomyVersion(taxonomy.getTaxonomyVersion()).stream()
                                .map(category -> new SelectableInterests.Item(
                                        category.getInterestCode(), category.getName(), category.getDisplayOrder()))
                                .toList()))
                .orElseGet(() -> new SelectableInterests(null, InterestPolicy.MAX_SELECTION, List.of()));
    }
}
