package kr.co.cking.creator.domain;

import org.springframework.test.util.ReflectionTestUtils;

/** 소개 문구·이미지를 정해 둔 Creator Space. 승인 경로는 기본값만 만들므로, 템플릿 시기에 만든 Space처럼 값이 있는 Space가 필요한 테스트가 쓴다. */
public final class CreatorSpaceFixtures {

    private CreatorSpaceFixtures() {
    }

    /** 만든 직후처럼 내용 버전은 1이다. */
    public static CreatorSpace space(Long creatorId, String introText, String profileImageUrl, String bannerImageUrl, String slug) {
        CreatorSpace space = CreatorSpace.createDefault(creatorId, slug);
        space.updateProfile(introText, profileImageUrl, bannerImageUrl);
        ReflectionTestUtils.setField(space, "contentRevision", 1L);
        return space;
    }
}
