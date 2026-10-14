package kr.co.cking.sanction.presentation.dto;

import kr.co.cking.sanction.application.dto.SpaceCommentBlockView;

import java.time.Instant;

/**
 * 크리에이터에게 보여주는 스페이스 댓글 작성 차단 응답.
 *
 * @param memberName 차단된 회원의 공개 닉네임
 * @param releasedAt 크리에이터가 해제한 시각. 해제하지 않았으면 null
 * @param active     응답 시점에 지금 유효한 차단인지
 */
public record CreatorCommentBlockResponse(
        Long sanctionId,
        Long memberId,
        String memberName,
        Instant startedAt,
        Instant endsAt,
        Instant releasedAt,
        boolean active
) {

    public static CreatorCommentBlockResponse from(SpaceCommentBlockView view) {
        return new CreatorCommentBlockResponse(
                view.sanctionId(), view.memberId(), view.memberName(), view.startedAt(), view.endsAt(),
                view.releasedAt(), view.active());
    }
}
