package kr.co.cking.post.domain;

/** 게시글 이미지 업로드 기록 상태. 전이 규칙은 V34 migration 주석과 도메인 README를 따른다. */
public enum PostImageStatus {
    UPLOADING,
    UPLOADED,
    DELETE_PENDING
}
