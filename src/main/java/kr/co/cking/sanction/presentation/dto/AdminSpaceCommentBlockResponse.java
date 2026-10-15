package kr.co.cking.sanction.presentation.dto;

import kr.co.cking.sanction.application.dto.SpaceCommentBlockView;

import java.time.Instant;

/**
 * 관리자에게 보여주는 스페이스 댓글 작성 차단 응답.
 *
 * @param creatorId  차단이 걸린 스페이스의 주인(Creator)
 * @param memberName 차단된 회원의 공개 닉네임
 * @param createdBy  차단을 건 회원(크리에이터 본인 또는 관리자)
 * @param releasedAt 해제한 시각. 해제하지 않았으면 null
 * @param active     응답 시점에 지금 유효한 차단인지
 */
public record AdminSpaceCommentBlockResponse(
        Long sanctionId,
        Long creatorId,
        Long memberId,
        String memberName,
        Long createdBy,
        Instant startedAt,
        Instant endsAt,
        Instant releasedAt,
        boolean active
) {

    public static AdminSpaceCommentBlockResponse from(SpaceCommentBlockView view) {
        return new AdminSpaceCommentBlockResponse(
                view.sanctionId(), view.spaceCreatorId(), view.memberId(), view.memberName(), view.createdBy(),
                view.startedAt(), view.endsAt(), view.releasedAt(), view.active());
    }
}
