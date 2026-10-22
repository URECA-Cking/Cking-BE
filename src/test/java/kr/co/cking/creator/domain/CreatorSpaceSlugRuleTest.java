package kr.co.cking.creator.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CreatorSpaceSlugRuleTest {

    @ParameterizedTest
    @ValueSource(strings = {"creator-{creatorId}", "c{creatorId}", "a1-b-{creatorId}", "-{creatorId}", "{creatorId}"})
    void acceptsRuleEndingWithPlaceholderAfterNonDigit(String slugRule) {
        assertThat(CreatorSpaceSlugRule.isValid(slugRule)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "creator-{creatorId}0", "{creatorId}-creator", "a-{creatorId}-1", "creator1{creatorId}",
            "Creator-{creatorId}", "creator_{creatorId}", "creator {creatorId}", "크리에이터-{creatorId}",
            "creator１{creatorId}", "creator-space", "{creatorId}-{creatorId}", "", "{creatorid}"
    })
    void rejectsRuleThatCanCollideAcrossTemplateChanges(String slugRule) {
        assertThat(CreatorSpaceSlugRule.isValid(slugRule)).isFalse();
    }

    @Test
    void rejectsNull() {
        assertThat(CreatorSpaceSlugRule.isValid(null)).isFalse();
    }

    @Test
    void previouslyCollidingRulePairIsNoLongerBothValid() {
        assertThat(CreatorSpaceSlugRule.isValid("creator-{creatorId}0")).isFalse();
        assertThat(CreatorSpaceSlugRule.isValid("creator-{creatorId}")).isTrue();
    }

    @Test
    void appliesCreatorIdToPlaceholder() {
        assertThat(CreatorSpaceSlugRule.apply("creator-{creatorId}", 42L)).isEqualTo("creator-42");
    }
}
