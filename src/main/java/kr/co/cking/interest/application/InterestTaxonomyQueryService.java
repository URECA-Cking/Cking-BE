package kr.co.cking.interest.application;

import java.util.Set;
import java.util.stream.Collectors;
import kr.co.cking.interest.repository.InterestCategoryRepository;
import kr.co.cking.interest.repository.InterestTaxonomyRepository;
import kr.co.cking.recommendation.application.TaxonomyCodes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 추천 근거가 참조하는 분류체계의 분야 코드를 조회한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InterestTaxonomyQueryService implements TaxonomyCodes {
    private final InterestTaxonomyRepository taxonomyRepository;
    private final InterestCategoryRepository categoryRepository;

    @Override
    public Set<String> activeCodes(String version, String hash) {
        return taxonomyRepository.findById(version)
                .filter(t -> t.isActive() && t.getTaxonomyHash().equals(hash))
                .map(t -> categoryRepository.findActiveByTaxonomyVersion(version).stream()
                        .map(c -> c.getInterestCode()).collect(Collectors.toSet()))
                .orElse(Set.of());
    }

    @Override
    public Set<String> registeredCodes(String version, String hash) {
        return taxonomyRepository.findById(version)
                .filter(t -> t.getTaxonomyHash().equals(hash))
                .map(t -> categoryRepository.findAllByTaxonomyVersion(version).stream()
                        .map(c -> c.getInterestCode()).collect(Collectors.toSet()))
                .orElse(Set.of());
    }
}
