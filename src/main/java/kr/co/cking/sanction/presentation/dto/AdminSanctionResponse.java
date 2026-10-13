package kr.co.cking.sanction.presentation.dto;

import kr.co.cking.sanction.application.dto.SanctionView;
import kr.co.cking.sanction.domain.SanctionScope;
import kr.co.cking.sanction.domain.SanctionSource;
import kr.co.cking.sanction.domain.SanctionType;

import java.time.Instant;

/**
 * 관리자용 제재 응답.
 *
 * @param causeCommentId 자동 경고의 원인 댓글. 수동 제한은 null
 * @param reason         수동 제한의 사유. 자동 경고는 null
 * @param releasedAt     관리자가 해제한 시각. 해제하지 않았으면 null
 * @param active         응답 시점에 지금 유효한 제한인지
 */
public record AdminSanctionResponse(
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

    public static AdminSanctionResponse from(SanctionView view) {
        return new AdminSanctionResponse(
                view.sanctionId(), view.memberId(), view.scope(), view.type(), view.source(), view.causeCommentId(),
                view.reason(), view.startedAt(), view.endsAt(), view.releasedAt(), view.createdBy(), view.releasedBy(),
                view.active());
    }
}
