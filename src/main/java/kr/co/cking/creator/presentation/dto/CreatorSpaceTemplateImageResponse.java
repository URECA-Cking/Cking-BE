package kr.co.cking.creator.presentation.dto;

/** 관리자가 올린 템플릿 이미지의 공개 CDN URL. 템플릿의 프로필·배너 URL 필드에 넣어 저장한다. */
public record CreatorSpaceTemplateImageResponse(String imageUrl) {
}
