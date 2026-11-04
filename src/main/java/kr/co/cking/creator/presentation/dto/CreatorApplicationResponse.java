package kr.co.cking.creator.presentation.dto;

import kr.co.cking.creator.domain.CreatorApplication;
import kr.co.cking.creator.domain.CreatorApplicationStatus;
import kr.co.cking.creator.domain.CreatorApplicationForm;
import kr.co.cking.creator.application.CreatorApplicationService;
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
            String rejectReason,
            String revisionReason,
            int submissionVersion
    ) {}

    public record Admin(
            Long applicationId,
            Long applicantUserId,
            String applicantName,
            CreatorApplicationStatus status,
            Instant requestedAt,
            Long reviewedBy,
            Instant reviewedAt,
            String rejectReason,
            String revisionReason,
            int submissionVersion
    ) {
        public static Admin from(CreatorApplication application, String applicantName) {
            return new Admin(
                    application.getId(), application.getMemberId(), applicantName, application.getStatus(),
                    application.getRequestedAt().toInstant(java.time.ZoneOffset.UTC), application.getReviewedBy(),
                    application.getReviewedAt() == null ? null : application.getReviewedAt().toInstant(java.time.ZoneOffset.UTC),
                    application.getRejectReason(), application.getRevisionReason(), application.getSubmissionVersion()
            );
        }
    }

    public record Detail(
            Long applicationId, Long applicantUserId, String applicantName, CreatorApplicationStatus status,
            CreatorApplicationForm submittedForm, CreatorApplicationForm draftForm,
            int submissionVersion, String revisionReason, String rejectReason,
            List<Submission> submissions, List<Review> reviews
    ) {
        public static Detail from(CreatorApplicationService.ApplicationDetail detail, boolean admin) {
            CreatorApplication application = detail.application();
            return new Detail(application.getId(), application.getMemberId(), detail.applicantName(), application.getStatus(),
                    application.getSubmittedForm(), admin ? null : application.getDraftForm(),
                    application.getSubmissionVersion(), application.getRevisionReason(), application.getRejectReason(),
                    detail.submissions().stream().map(item -> new Submission(item.getVersion(), item.getForm(),
                            item.getSubmittedAt().toInstant(java.time.ZoneOffset.UTC))).toList(),
                    detail.reviews().stream().map(item -> new Review(item.getAction(), item.getSubmissionVersion(),
                            admin ? item.getActorId() : null, item.getReason(),
                            item.getCreatedAt().toInstant(java.time.ZoneOffset.UTC))).toList());
        }
    }

    public record Submission(int version, CreatorApplicationForm form, Instant submittedAt) {
    }

    public record Review(String action, int submissionVersion, Long actorId, String reason, Instant createdAt) {
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
