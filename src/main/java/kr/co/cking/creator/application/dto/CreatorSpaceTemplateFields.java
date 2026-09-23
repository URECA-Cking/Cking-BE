package kr.co.cking.creator.application.dto;

/** 크리에이터 스페이스 템플릿 생성·수정에 필요한 검증 전 필드 데이터를 전달한다. */
public record CreatorSpaceTemplateFields(
        String introText,
        String profileImageUrl,
        String bannerImageUrl,
        String slugRule,
        boolean homeTabEnabled,
        boolean missionsTabEnabled,
        boolean postsTabEnabled,
        boolean eventsTabEnabled
) {
}
