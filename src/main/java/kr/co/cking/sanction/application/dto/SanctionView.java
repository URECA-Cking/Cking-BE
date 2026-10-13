package kr.co.cking.sanction.application.dto;

import kr.co.cking.sanction.domain.MemberSanction;
import kr.co.cking.sanction.domain.SanctionScope;
import kr.co.cking.sanction.domain.SanctionSource;
import kr.co.cking.sanction.domain.SanctionType;

import java.time.Instant;

/**
 * 제재 한 건의 조회 결과.
 *
 * @param active 조회 시각 기준으로 지금 유효한 제한인지
 */
public record SanctionView(
        Long sanctionId,
        Long memberId,
        SanctionScope scope,
        SanctionType type,
        SanctionSource source,
        Long causeCommentId,
        String reason,
        Instant startedAt,
        Instant endsAt,
        Instant releasedAt,
        Long createdBy,
        Long releasedBy,
        boolean active
) {

    public static SanctionView of(MemberSanction sanction, Instant now) {
        return new SanctionView(
                sanction.getSanctionId(),
                sanction.getMemberId(),
                sanction.getScope(),
                sanction.getType(),
                sanction.getSource(),
                sanction.getCauseCommentId(),
                sanction.getReason(),
                sanction.getStartedAt(),
                sanction.getEndsAt(),
                sanction.getReleasedAt(),
                sanction.getCreatedBy(),
                sanction.getReleasedBy(),
                sanction.isActiveAt(now));
    }
}
