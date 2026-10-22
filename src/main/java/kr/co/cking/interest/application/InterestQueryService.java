package kr.co.cking.interest.application;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import kr.co.cking.interest.application.dto.SelectableInterests;
import kr.co.cking.interest.domain.InterestPolicy;
import kr.co.cking.interest.domain.InterestSubtopic;
import kr.co.cking.interest.domain.InterestSubtopicSet;
import kr.co.cking.interest.domain.InterestTaxonomy;
import kr.co.cking.interest.repository.InterestCategoryRepository;
import kr.co.cking.interest.repository.InterestSubtopicRepository;
import kr.co.cking.interest.repository.InterestSubtopicSetRepository;
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
    private final InterestSubtopicSetRepository subtopicSetRepository;
    private final InterestSubtopicRepository subtopicRepository;

    /**
     * 활성 분류체계의 활성 분야를 노출 순서대로 반환하고, 활성 분류체계에 묶인 활성 세부 세트의 활성 세부 주제를 분야 아래에
     * 붙인다. 활성 분류체계가 없으면 빈 목록이고, 활성 세부 세트가 없으면 세부 목록만 빈다.
     */
    public SelectableInterests findSelectable() {
        return taxonomyRepository.findByActiveTrue()
                .map(this::toSelectable)
                .orElseGet(() -> new SelectableInterests(
                        null, InterestPolicy.MAX_SELECTION, null, InterestPolicy.MAX_SUBTOPIC_PER_PARENT, List.of()));
    }

    private SelectableInterests toSelectable(InterestTaxonomy taxonomy) {
        InterestSubtopicSet set = subtopicSetRepository.findByActiveTrue()
                .filter(active -> active.getParentTaxonomyVersion().equals(taxonomy.getTaxonomyVersion()))
                .orElse(null);
        Map<String, List<SelectableInterests.Subtopic>> subtopicsByParent = set == null
                ? Map.of()
                : subtopicRepository.findActiveBySubtopicVersion(set.getSubtopicVersion()).stream()
                        .collect(Collectors.groupingBy(InterestSubtopic::getParentInterestCode,
                                Collectors.mapping(subtopic -> new SelectableInterests.Subtopic(
                                        subtopic.getSubtopicCode(), subtopic.getName(), subtopic.getDisplayOrder()),
                                        Collectors.toList())));
        return new SelectableInterests(
                taxonomy.getTaxonomyVersion(),
                InterestPolicy.MAX_SELECTION,
                set == null ? null : set.getSubtopicVersion(),
                InterestPolicy.MAX_SUBTOPIC_PER_PARENT,
                categoryRepository.findActiveByTaxonomyVersion(taxonomy.getTaxonomyVersion()).stream()
                        .map(category -> new SelectableInterests.Item(
                                category.getInterestCode(), category.getName(), category.getDisplayOrder(),
                                subtopicsByParent.getOrDefault(category.getInterestCode(), List.of())))
                        .toList());
    }
}
