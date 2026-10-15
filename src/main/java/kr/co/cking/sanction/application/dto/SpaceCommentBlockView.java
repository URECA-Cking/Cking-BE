package kr.co.cking.sanction.application.dto;

import kr.co.cking.sanction.domain.MemberSanction;

import java.time.Instant;

/**
 * 스페이스 댓글 작성 차단 한 건의 조회 결과(이슈 #579, #584).
 *
 * @param memberName     차단된 회원의 공개 닉네임. 회원 정보를 찾지 못하면 null
 * @param spaceCreatorId 차단이 걸린 스페이스의 주인(Creator)
 * @param createdBy      차단을 건 회원(크리에이터 본인 또는 관리자)
 * @param active         조회 시각 기준으로 지금 유효한 차단인지
 */
public record SpaceCommentBlockView(
        Long sanctionId,
        Long memberId,
        String memberName,
        Long spaceCreatorId,
        Long createdBy,
        Instant startedAt,
        Instant endsAt,
        Instant releasedAt,
        boolean active
) {

    public static SpaceCommentBlockView of(MemberSanction sanction, String memberName, Instant now) {
        return new SpaceCommentBlockView(
                sanction.getSanctionId(),
                sanction.getMemberId(),
                memberName,
                sanction.getSpaceCreatorId(),
                sanction.getCreatedBy(),
                sanction.getStartedAt(),
                sanction.getEndsAt(),
                sanction.getReleasedAt(),
                sanction.isActiveAt(now));
    }
}
