package kr.co.cking.creator.presentation.dto;

import kr.co.cking.creator.domain.CreatorApplication;
import kr.co.cking.creator.domain.CreatorApplicationStatus;
import org.springframework.data.domain.Page;

import java.time.Instant;
import java.util.List;

public final class CreatorApplicationResponse {

    private CreatorApplicationResponse() {
    }

    public record Result(Long applicationId, CreatorApplicationStatus status) {
        public static Result from(CreatorApplication application) {
            return new Result(application.getId(), application.getStatus());
        }
    }

    public record Mine(
            Long applicationId,
            CreatorApplicationStatus status,
            Instant requestedAt,
            Instant reviewedAt,
            String rejectReason
    ) {}

    public record Admin(
            Long applicationId,
            Long applicantUserId,
            String applicantName,
            CreatorApplicationStatus status,
            Instant requestedAt,
            Long reviewedBy,
            Instant reviewedAt,
            String rejectReason
    ) {
        public static Admin from(CreatorApplication application, String applicantName) {
            return new Admin(
                    application.getId(), application.getMemberId(), applicantName, application.getStatus(),
                    application.getRequestedAt().toInstant(java.time.ZoneOffset.UTC), application.getReviewedBy(),
                    application.getReviewedAt() == null ? null : application.getReviewedAt().toInstant(java.time.ZoneOffset.UTC),
                    application.getRejectReason()
            );
        }
    }

    public record PageResult<T>(
            List<T> items,
            int page,
            int size,
            long totalElements,
            int totalPages,
            boolean hasNext
    ) {
        public static <T> PageResult<T> from(Page<?> source, List<T> items) {
            return new PageResult<>(
                    items, source.getNumber(), source.getSize(), source.getTotalElements(),
                    source.getTotalPages(), source.hasNext()
            );
        }
    }
}
