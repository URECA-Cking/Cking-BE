package kr.co.cking.member.domain;

/** 개인 프로필 이미지 업로드의 저장소·연결 상태다. */
public enum MemberProfileImageStatus {
    UPLOADING,
    UPLOADED,
    LINKED,
    DELETE_PENDING
}
