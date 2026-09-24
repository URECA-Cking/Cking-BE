package kr.co.cking.creator.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CreatorSpaceTest {

    @Test
    void fromTemplateCopiesTemplateValues() {
        CreatorSpaceTemplate template = new CreatorSpaceTemplate(
                1L, "소개", "https://img/profile.png", "https://img/banner.png", "creator-{creatorId}",
                true, false, true, false
        );

        CreatorSpace space = CreatorSpace.fromTemplate(42L, template, "creator-42");

        assertThat(space.getCreatorId()).isEqualTo(42L);
        assertThat(space.getIntroText()).isEqualTo("소개");
        assertThat(space.getProfileImageUrl()).isEqualTo("https://img/profile.png");
        assertThat(space.getBannerImageUrl()).isEqualTo("https://img/banner.png");
        assertThat(space.getSlug()).isEqualTo("creator-42");
        assertThat(space.isHomeTabEnabled()).isTrue();
        assertThat(space.isMissionsTabEnabled()).isFalse();
        assertThat(space.isPostsTabEnabled()).isTrue();
        assertThat(space.isEventsTabEnabled()).isFalse();
    }

    /** Space는 템플릿 값을 생성 시점에 복사만 할 뿐 템플릿을 참조로 들고 있지 않으므로, 이후 템플릿 수정은 이미 만든 Space에 영향을 주지 않는다. */
    @Test
    void spaceIsIndependentFromLaterTemplateChanges() {
        CreatorSpaceTemplate template = new CreatorSpaceTemplate(
                1L, "원래 소개", "https://img/profile.png", "https://img/banner.png", "creator-{creatorId}",
                true, true, true, true
        );
        CreatorSpace space = CreatorSpace.fromTemplate(42L, template, "creator-42");

        template.update(9L, "바뀐 소개", "https://img/new-profile.png", "https://img/new-banner.png",
                "new-{creatorId}", false, false, false, false);

        assertThat(space.getIntroText()).isEqualTo("원래 소개");
        assertThat(space.getProfileImageUrl()).isEqualTo("https://img/profile.png");
        assertThat(space.isHomeTabEnabled()).isTrue();
    }
}
