package kr.co.cking.creator.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CreatorSpaceTest {

    @Test
    void fromTemplateCopiesTemplateValues() {
        CreatorSpaceTemplate template = new CreatorSpaceTemplate(
                1L, "소개", "https://img/profile.png", "https://img/banner.png", "creator-{creatorId}"
        );

        CreatorSpace space = CreatorSpace.fromTemplate(42L, template, "creator-42");

        assertThat(space.getCreatorId()).isEqualTo(42L);
        assertThat(space.getIntroText()).isEqualTo("소개");
        assertThat(space.getProfileImageUrl()).isEqualTo("https://img/profile.png");
        assertThat(space.getBannerImageUrl()).isEqualTo("https://img/banner.png");
        assertThat(space.getSlug()).isEqualTo("creator-42");
    }

    /** 홈·프로필 수정은 소개·이미지만 바꾸고 slug와 creatorId는 그대로 둔다. */
    @Test
    void updateProfileChangesProfileFieldsButKeepsSlug() {
        CreatorSpaceTemplate template = new CreatorSpaceTemplate(
                1L, "소개", "https://img/profile.png", "https://img/banner.png", "creator-{creatorId}"
        );
        CreatorSpace space = CreatorSpace.fromTemplate(42L, template, "creator-42");

        space.updateProfile("새 소개", "https://img/p2.png", "https://img/b2.png");

        assertThat(space.getIntroText()).isEqualTo("새 소개");
        assertThat(space.getProfileImageUrl()).isEqualTo("https://img/p2.png");
        assertThat(space.getBannerImageUrl()).isEqualTo("https://img/b2.png");
        assertThat(space.getSlug()).isEqualTo("creator-42");
        assertThat(space.getCreatorId()).isEqualTo(42L);
    }

    /** Space는 템플릿 값을 생성 시점에 복사만 할 뿐 템플릿을 참조로 들고 있지 않으므로, 이후 템플릿 수정은 이미 만든 Space에 영향을 주지 않는다. */
    @Test
    void spaceIsIndependentFromLaterTemplateChanges() {
        CreatorSpaceTemplate template = new CreatorSpaceTemplate(
                1L, "원래 소개", "https://img/profile.png", "https://img/banner.png", "creator-{creatorId}"
        );
        CreatorSpace space = CreatorSpace.fromTemplate(42L, template, "creator-42");

        template.update(9L, "바뀐 소개", "https://img/new-profile.png", "https://img/new-banner.png",
                "new-{creatorId}");

        assertThat(space.getIntroText()).isEqualTo("원래 소개");
        assertThat(space.getProfileImageUrl()).isEqualTo("https://img/profile.png");
    }
}
