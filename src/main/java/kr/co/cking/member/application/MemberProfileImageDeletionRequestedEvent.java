package kr.co.cking.member.application;

/** 프로필 교체·기본 이미지 복귀가 commit된 뒤 기존 Object를 정리하는 이벤트다. */
public record MemberProfileImageDeletionRequestedEvent(String objectKey) {
}
