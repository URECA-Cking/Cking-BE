package kr.co.cking.post.filter;

/** 필터 서비스 호출 경계. 댓글 본문 하나를 보내 PASS/BLOCK 판정을 받는다. */
public interface CommentFilterClient {

    /**
     * @throws CommentFilterException 필터 서비스 장애·타임아웃·잘못된 응답. 호출자는 댓글을 통과 상태로 두고 나중에 다시 판정한다.
     */
    CommentFilterResult moderate(Long commentId, String content);
}
