package kr.co.cking.creator.domain;

import java.util.List;

/** 심사 시점에 제출한 신청서의 불변 값이다. */
public record CreatorApplicationForm(
        String preferredActivityName,
        String taxonomyVersion,
        List<String> categoryCodes,
        String introduction,
        List<String> evidenceUrls,
        String activityPlan,
        List<String> platforms,
        boolean agreeToCreatorPolicy
) {
    public CreatorApplicationForm {
        categoryCodes = List.copyOf(categoryCodes);
        evidenceUrls = List.copyOf(evidenceUrls);
        platforms = List.copyOf(platforms);
    }
}
