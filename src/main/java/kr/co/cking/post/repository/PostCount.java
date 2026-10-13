package kr.co.cking.post.repository;

/** 게시글별 집계 결과(좋아요 수·댓글 수). */
public interface PostCount {

    Long getPostId();

    long getTotal();
}
