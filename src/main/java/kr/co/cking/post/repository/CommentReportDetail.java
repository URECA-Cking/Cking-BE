package kr.co.cking.post.repository;

/** 기타(OTHER) 신고의 설명. 신고자는 담지 않는다. */
public interface CommentReportDetail {

    Long getCommentId();

    String getDetail();
}
