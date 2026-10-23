package kr.co.cking.creator.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CreatorSpaceTest {

    /** 승인 시 만드는 Space는 기본 소개 문구에 이미지가 없다. 이미지는 화면이 기본 이미지로 채운다. */
    @Test
    void createDefaultHasDefaultIntroAndNoImages() {
        CreatorSpace space = CreatorSpace.createDefault(42L, "creator-42");

        assertThat(space.getCreatorId()).isEqualTo(42L);
        assertThat(space.getIntroText()).isEqualTo(CreatorSpace.DEFAULT_INTRO_TEXT);
        assertThat(space.getProfileImageUrl()).isNull();
        assertThat(space.getDefaultProfileImageUrl()).isNull();
        assertThat(space.getBannerImageUrl()).isNull();
        assertThat(space.getDefaultBannerImageUrl()).isNull();
        assertThat(space.getSlug()).isEqualTo("creator-42");
        assertThat(space.getContentRevision()).isEqualTo(1L);
    }

    /** 홈·프로필 수정은 소개·이미지만 바꾸고 slug와 creatorId는 그대로 둔다. */
    @Test
    void updateProfileChangesProfileFieldsButKeepsSlug() {
        CreatorSpace space = CreatorSpace.createDefault(42L, "creator-42");

        space.updateProfile("새 소개", "https://img/p2.png", "https://img/b2.png");

        assertThat(space.getIntroText()).isEqualTo("새 소개");
        assertThat(space.getProfileImageUrl()).isEqualTo("https://img/p2.png");
        assertThat(space.getBannerImageUrl()).isEqualTo("https://img/b2.png");
        assertThat(space.getSlug()).isEqualTo("creator-42");
        assertThat(space.getCreatorId()).isEqualTo(42L);
    }

    /** 이미지 URL을 비우거나 생략하면 이미지 없음으로 저장하고, 기본 이미지(복귀 대상)도 없음이 된다. */
    @Test
    void blankOrMissingImageUrlMeansNoImage() {
        CreatorSpace space = CreatorSpaceFixtures.space(42L, "소개", "https://img/p.png", "https://img/b.png", "creator-42");

        space.updateProfile("소개", " ", null);

        assertThat(space.getProfileImageUrl()).isNull();
        assertThat(space.getDefaultProfileImageUrl()).isNull();
        assertThat(space.getBannerImageUrl()).isNull();
        assertThat(space.getDefaultBannerImageUrl()).isNull();
    }

    /** 업로드 이미지 연결을 풀면 기본 이미지로 돌아가는데, 기본 이미지가 없으면 이미지 없음이 된다. */
    @Test
    void restoringDefaultImagesOfDefaultSpaceMeansNoImage() {
        CreatorSpace space = CreatorSpace.createDefault(42L, "creator-42");
        space.updateProfileImage("creator-space-profile-images/42/p.jpg", "https://cdn/p.jpg");
        space.updateBannerImage("creator-space-profile-images/42/banner/b.jpg", "https://cdn/b.jpg");

        space.restoreDefaultProfileImage();
        space.restoreDefaultBannerImage();

        assertThat(space.getProfileImageKey()).isNull();
        assertThat(space.getProfileImageUrl()).isNull();
        assertThat(space.getBannerImageKey()).isNull();
        assertThat(space.getBannerImageUrl()).isNull();
    }
}
