package kr.co.cking.interest.application;

import java.util.List;
import kr.co.cking.interest.domain.InterestTaxonomy;
import kr.co.cking.interest.domain.InterestTaxonomyHash;
import kr.co.cking.interest.repository.InterestCategoryRepository;
import kr.co.cking.interest.repository.InterestTaxonomyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 저장된 분야 행에서 다시 계산한 해시가 등록된 {@code taxonomy_hash}와 같은지 확인한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InterestTaxonomyVerifier {

    private final InterestTaxonomyRepository taxonomyRepository;
    private final InterestCategoryRepository categoryRepository;

    /** 등록되지 않은 버전이거나 행이 하나도 없으면 일치하지 않는 것으로 본다. */
    public boolean matchesRegisteredHash(String taxonomyVersion) {
        InterestTaxonomy taxonomy = taxonomyRepository.findById(taxonomyVersion).orElse(null);
        if (taxonomy == null) {
            return false;
        }
        List<InterestTaxonomyHash.Row> rows = categoryRepository.findAllByTaxonomyVersion(taxonomyVersion).stream()
                .map(category -> new InterestTaxonomyHash.Row(
                        category.getInterestCode(), category.getName(), category.getDescription()))
                .toList();
        return !rows.isEmpty() && InterestTaxonomyHash.compute(rows).equals(taxonomy.getTaxonomyHash());
    }
}
