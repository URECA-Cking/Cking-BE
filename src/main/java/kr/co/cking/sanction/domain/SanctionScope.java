package kr.co.cking.sanction.domain;

/** 제재가 적용되는 범위. */
public enum SanctionScope {
    /** 플랫폼 전체. 관리자가 걸거나 댓글 숨김 경고로 생긴다(이슈 #569). */
    PLATFORM,
    /** 한 크리에이터의 스페이스 안. 그 크리에이터가 건다(이슈 #579). */
    SPACE
}
