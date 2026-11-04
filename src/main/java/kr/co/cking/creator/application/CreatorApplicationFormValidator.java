package kr.co.cking.creator.application;

import java.net.URI;
import java.util.List;
import java.util.Set;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.CreatorActivityName;
import kr.co.cking.creator.domain.CreatorApplicationForm;
import kr.co.cking.interest.domain.InterestTaxonomy;
import kr.co.cking.interest.repository.InterestCategoryRepository;
import kr.co.cking.interest.repository.InterestTaxonomyRepository;
import kr.co.cking.member.application.MemberNicknameContentPolicy;
import kr.co.cking.member.domain.MemberNickname;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 현재 활성 관심 분야와 기존 활동명 정책을 신청서에도 적용한다. */
@Component
@RequiredArgsConstructor
public class CreatorApplicationFormValidator {
    private static final Set<String> PLATFORMS = Set.of("YOUTUBE", "INSTAGRAM", "TIKTOK", "BLOG", "OTHER");

    private final InterestTaxonomyRepository taxonomyRepository;
    private final InterestCategoryRepository categoryRepository;
    private final MemberNicknameContentPolicy contentPolicy;

    public CreatorApplicationForm validate(CreatorApplicationForm form) {
        if (form == null || !form.agreeToCreatorPolicy()
                || !length(form.introduction(), 30, 500) || !length(form.activityPlan(), 50, 1000)
                || !count(form.categoryCodes(), 1, 3) || !count(form.evidenceUrls(), 1, 3)
                || !count(form.platforms(), 0, 5)) {
            throw invalid();
        }
        try {
            CreatorActivityName.of(form.preferredActivityName());
            contentPolicy.validate(MemberNickname.of(form.preferredActivityName()));
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
        if (form.evidenceUrls().stream().anyMatch(url -> !validUrl(url))
                || form.platforms().stream().anyMatch(platform -> !PLATFORMS.contains(platform))) {
            throw invalid();
        }
        InterestTaxonomy taxonomy = taxonomyRepository.findByActiveTrue().orElseThrow(this::invalid);
        Set<String> available = categoryRepository.findActiveByTaxonomyVersion(taxonomy.getTaxonomyVersion()).stream()
                .map(category -> category.getInterestCode()).collect(java.util.stream.Collectors.toSet());
        if (!available.containsAll(form.categoryCodes())) {
            throw invalid();
        }
        return new CreatorApplicationForm(form.preferredActivityName().trim(), taxonomy.getTaxonomyVersion(),
                form.categoryCodes(), form.introduction().trim(), form.evidenceUrls(), form.activityPlan().trim(),
                form.platforms(), true);
    }

    private boolean count(List<String> values, int min, int max) {
        return values != null && values.size() >= min && values.size() <= max
                && values.stream().noneMatch(value -> value == null || value.isBlank())
                && Set.copyOf(values).size() == values.size();
    }

    private boolean length(String value, int min, int max) {
        return value != null && value.trim().length() >= min && value.length() <= max;
    }

    private boolean validUrl(String value) {
        if (value == null || value.length() > 2048) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && !uri.getHost().isBlank() && uri.getUserInfo() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private BusinessException invalid() {
        return new BusinessException(CommonErrorCode.VALIDATION_FAILED);
    }
}
