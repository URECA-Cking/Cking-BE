package kr.co.cking.post.domain;

/** 댓글 필터 판정 상태. 의미와 전이는 V46 migration 주석을 따른다. */
public enum CommentFilterStatus {
    PENDING,
    DONE,
    FAILED
}
