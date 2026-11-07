package kr.co.cking.creator.presentation.dto;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorStatus;
import kr.co.cking.creator.domain.CreatorStatusHistory;
import org.springframework.data.domain.Page;

public final class CreatorStatusResponse {
    private CreatorStatusResponse() {
    }

    public record CreatorItem(Long creatorId, Long memberId, String activityName,
                              CreatorStatus status, boolean canOperate, String publicNotice,
                              Instant createdAt) {
        public static CreatorItem from(Creator creator) {
            return new CreatorItem(creator.getCreatorId(), creator.getMemberId(), creator.publicDisplayName(),
                    creator.getStatus(), creator.canOperate(), creator.getStatusNotice(),
                    creator.getCreatedAt().toInstant(ZoneOffset.UTC));
        }
    }

    public record Mine(Long creatorId, CreatorStatus status, boolean canOperate, String publicNotice) {
        public static Mine from(Creator creator) {
            return new Mine(creator.getCreatorId(), creator.getStatus(), creator.canOperate(),
                    creator.getStatusNotice());
        }
    }

    public record Changed(Long creatorId, CreatorStatus previousStatus, CreatorStatus status,
                          String publicNotice, String requestId, Instant changedAt) {
        public static Changed from(CreatorStatusHistory history) {
            return new Changed(history.getCreatorId(), history.getPreviousStatus(), history.getNewStatus(),
                    history.getNewStatus() == CreatorStatus.ACTIVE ? null : history.getPublicNotice(),
                    history.getRequestId(), history.getCreatedAt());
        }
    }

    public record History(Long id, Long creatorId, CreatorStatus previousStatus, CreatorStatus status,
                          String reasonCode, String detailReason, String publicNotice,
                          Long adminId, String requestId, Instant createdAt) {
        public static History from(CreatorStatusHistory history) {
            return new History(history.getId(), history.getCreatorId(), history.getPreviousStatus(),
                    history.getNewStatus(), history.getReasonCode(), history.getDetailReason(),
                    history.getPublicNotice(), history.getAdminId(), history.getRequestId(),
                    history.getCreatedAt());
        }
    }

    public record PageResult<T>(List<T> items, int page, int size, long totalElements,
                                int totalPages, boolean hasNext) {
        public static <T> PageResult<T> from(Page<?> source, List<T> items) {
            return new PageResult<>(items, source.getNumber(), source.getSize(), source.getTotalElements(),
                    source.getTotalPages(), source.hasNext());
        }
    }
}
