package kr.co.cking.sanction.application.dto;

import kr.co.cking.sanction.domain.MemberSanction;

import java.time.Instant;

/**
 * 크리에이터가 자기 스페이스에서 건 댓글 작성 차단 한 건의 조회 결과(이슈 #579).
 *
 * @param memberName 차단된 회원의 공개 닉네임. 회원 정보를 찾지 못하면 null
 * @param active     조회 시각 기준으로 지금 유효한 차단인지
 */
public record SpaceCommentBlockView(
        Long sanctionId,
        Long memberId,
        String memberName,
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
                sanction.getStartedAt(),
                sanction.getEndsAt(),
                sanction.getReleasedAt(),
                sanction.isActiveAt(now));
    }
}
