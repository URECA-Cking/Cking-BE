package kr.co.cking.post.domain;

/** 댓글 신고 사유. 설명은 {@link #OTHER}일 때만 받는다. */
public enum CommentReportReason {
    /** 욕설·혐오 */
    ABUSE,
    /** 스팸·홍보 */
    SPAM,
    /** 개인정보 노출 */
    PRIVACY,
    /** 기타. 짧은 설명이 필요하다 */
    OTHER
}
