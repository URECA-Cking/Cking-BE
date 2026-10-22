package kr.co.cking.post.domain;

/** 필터의 최종 판정. 필터 서비스는 PASS와 BLOCK 두 값만 돌려준다. */
public enum CommentFilterAction {
    PASS,
    BLOCK
}
