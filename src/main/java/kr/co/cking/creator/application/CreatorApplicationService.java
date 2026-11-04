package kr.co.cking.creator.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorApplication;
import kr.co.cking.creator.domain.CreatorApplicationStatus;
import kr.co.cking.creator.domain.CreatorApplicationForm;
import kr.co.cking.creator.domain.CreatorApplicationSubmission;
import kr.co.cking.creator.domain.CreatorApplicationReviewHistory;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.repository.CreatorApplicationRepository;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorApplicationSubmissionRepository;
import kr.co.cking.creator.repository.CreatorApplicationReviewHistoryRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.application.MissionInitializationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class CreatorApplicationService {

    private final MemberRepository memberRepository;
    private final CreatorRepository creatorRepository;
    private final CreatorApplicationRepository applicationRepository;
    private final CreatorApplicationLockManager lockManager;
    private final MissionInitializationService missionInitializationService;
    private final CreatorSpaceService creatorSpaceService;
    private final CreatorActivityNameService activityNameService;
    private final CreatorApplicationFormValidator formValidator;
    private final CreatorApplicationSubmissionRepository submissionRepository;
    private final CreatorApplicationReviewHistoryRepository reviewHistoryRepository;

    public ApplyResult apply(Long memberId) {
        return apply(memberId, null);
    }

    public ApplyResult apply(Long memberId, CreatorApplicationForm form) {
        return lockManager.execute("creator-application:member:" + memberId, () -> applyLocked(memberId, form));
    }

    private ApplyResult applyLocked(Long memberId, CreatorApplicationForm form) {
        Member member = requireMember(memberId);
        member.requireProfileConfigured();
        if (creatorRepository.existsByMemberId(memberId)) {
            throw new BusinessException(CreatorErrorCode.INVALID_STATE);
        }
        // 처리 중인 신청은 새로 만들지 않고 기존 결과를 반환한다.
        Optional<CreatorApplication> existing = applicationRepository.findFirstByMemberIdAndStatusInOrderByIdDesc(
                memberId, List.of(CreatorApplicationStatus.PENDING, CreatorApplicationStatus.NEEDS_REVISION));
        if (existing.isPresent()) {
            if (existing.get().getStatus() == CreatorApplicationStatus.NEEDS_REVISION
                    || form != null && !sameSubmittedForm(form, existing.get().getSubmittedForm())) {
                throw new BusinessException(CreatorErrorCode.APPLICATION_CONTENT_CONFLICT);
            }
            return new ApplyResult(existing.get(), false);
        }
        CreatorApplicationForm validated = form == null ? null : formValidator.validate(form);
        CreatorApplication application = applicationRepository.save(validated == null
                ? new CreatorApplication(memberId) : new CreatorApplication(memberId, validated));
        if (validated != null) {
            submissionRepository.save(new CreatorApplicationSubmission(
                    application.getId(), application.getSubmissionVersion(), validated));
        }
        return new ApplyResult(application, true);
    }

    /** 이미 접수한 동일 요청은 taxonomy 교체 후에도 원래 결과를 반환한다. */
    private boolean sameSubmittedForm(CreatorApplicationForm requested, CreatorApplicationForm submitted) {
        return submitted != null
                && Objects.equals(requested.preferredActivityName(), submitted.preferredActivityName())
                && Objects.equals(requested.categoryCodes(), submitted.categoryCodes())
                && Objects.equals(requested.introduction() == null ? null : requested.introduction().trim(),
                        submitted.introduction())
                && Objects.equals(requested.evidenceUrls(), submitted.evidenceUrls())
                && Objects.equals(requested.activityPlan() == null ? null : requested.activityPlan().trim(),
                        submitted.activityPlan())
                && Objects.equals(requested.platforms(), submitted.platforms())
                && requested.agreeToCreatorPolicy() == submitted.agreeToCreatorPolicy();
    }

    @Transactional(readOnly = true)
    public Page<CreatorApplication> findMine(Long memberId, Pageable pageable) {
        requireMember(memberId);
        return applicationRepository.findByMemberIdOrderByRequestedAtDescIdDesc(memberId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<AdminApplication> findAllForAdmin(
            Long adminId,
            CreatorApplicationStatus status,
            Pageable pageable
    ) {
        requireAdmin(adminId);
        Page<CreatorApplication> applications = status == null
                ? applicationRepository.findAllByOrderByRequestedAtAscIdAsc(pageable)
                : applicationRepository.findByStatusOrderByRequestedAtAscIdAsc(status, pageable);
        Map<Long, String> applicantNames = memberRepository.findByMemberIdIn(applications.stream()
                        .map(CreatorApplication::getMemberId).toList())
                .stream().collect(Collectors.toMap(Member::getMemberId, Member::publicDisplayName));
        List<AdminApplication> items = applications.stream()
                .map(application -> new AdminApplication(application, applicantNames.get(application.getMemberId())))
                .toList();
        return new org.springframework.data.domain.PageImpl<>(items, pageable, applications.getTotalElements());
    }

    public CreatorApplication approve(Long adminId, Long applicationId) {
        return lockManager.execute("creator-application:review:" + applicationId,
                // 첫 JPA 조회 전에 이름 lock을 얻어 대기 중 commit한 활동명을 같은 snapshot에서 본다.
                () -> lockManager.executeActivityNameAllocation(() -> approveLocked(adminId, applicationId)));
    }

    /** 신청을 승인하고 Creator·기본 미션·Creator Space를 하나의 트랜잭션으로 생성한다. */
    private CreatorApplication approveLocked(Long adminId, Long applicationId) {
        requireAdmin(adminId);
        CreatorApplication application = getApplication(applicationId);
        Member applicant = requireMember(application.getMemberId());
        if (creatorRepository.existsByMemberId(application.getMemberId())) {
            throw new BusinessException(CreatorErrorCode.INVALID_STATE);
        }
        CreatorApplicationForm submitted = application.getSubmittedForm();
        if (submitted == null) {
            throw new BusinessException(CreatorErrorCode.APPLICATION_FORM_REQUIRED);
        }
        CreatorApplicationForm current = formValidator.validate(submitted);
        if (!submitted.taxonomyVersion().equals(current.taxonomyVersion())) {
            throw new BusinessException(CreatorErrorCode.APPLICATION_FORM_REQUIRED);
        }
        application.approve(adminId);
        Creator creator = creatorRepository.save(new Creator(
                application.getMemberId(), applicant.getName(),
                activityNameService.initialAvailableNameUnderAllocationLock(
                        submitted.preferredActivityName(), applicant.getName(), application.getMemberId())));
        missionInitializationService.initializeDefaultMissions(creator.getCreatorId());
        creatorSpaceService.createIfAbsent(creator.getCreatorId());
        recordHistory(application, "APPROVED", adminId, null);
        return application;
    }

    public CreatorApplication reject(Long adminId, Long applicationId, String rejectReason) {
        return lockManager.execute("creator-application:review:" + applicationId,
                () -> rejectLocked(adminId, applicationId, rejectReason));
    }

    private CreatorApplication rejectLocked(Long adminId, Long applicationId, String rejectReason) {
        requireAdmin(adminId);
        CreatorApplication application = getApplication(applicationId);
        application.reject(adminId, rejectReason.trim());
        recordHistory(application, "REJECTED", adminId, rejectReason.trim());
        return application;
    }

    public CreatorApplication requestRevision(Long adminId, Long applicationId, String reason) {
        return lockManager.execute("creator-application:review:" + applicationId, () -> {
            requireAdmin(adminId);
            CreatorApplication application = getApplication(applicationId);
            String normalizedReason = requireReason(reason);
            application.requestRevision(normalizedReason);
            recordHistory(application, "NEEDS_REVISION", adminId, normalizedReason);
            return application;
        });
    }

    private String requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        return reason.trim();
    }

    public CreatorApplication updateDraft(Long memberId, Long applicationId, CreatorApplicationForm form) {
        return lockManager.execute("creator-application:review:" + applicationId, () -> {
            CreatorApplication application = getOwnedApplication(memberId, applicationId);
            application.updateDraft(formValidator.validate(form));
            return application;
        });
    }

    public CreatorApplication resubmit(Long memberId, Long applicationId) {
        return lockManager.execute("creator-application:review:" + applicationId, () -> {
            CreatorApplication application = getOwnedApplication(memberId, applicationId);
            if (application.getDraftForm() != null) {
                CreatorApplicationForm current = formValidator.validate(application.getDraftForm());
                application.updateDraft(current);
            }
            application.resubmit();
            submissionRepository.save(new CreatorApplicationSubmission(
                    applicationId, application.getSubmissionVersion(), application.getSubmittedForm()));
            recordHistory(application, "RESUBMITTED", memberId, null);
            return application;
        });
    }

    @Transactional(readOnly = true)
    public ApplicationDetail findDetail(Long actorId, Long applicationId, boolean admin) {
        if (admin) {
            requireAdmin(actorId);
        }
        CreatorApplication application = admin ? getApplication(applicationId) : getOwnedApplication(actorId, applicationId);
        return new ApplicationDetail(application, requireMember(application.getMemberId()).publicDisplayName(),
                submissionRepository.findByApplicationIdOrderByVersionAsc(applicationId),
                reviewHistoryRepository.findByApplicationIdOrderByIdAsc(applicationId));
    }

    private CreatorApplication getOwnedApplication(Long memberId, Long applicationId) {
        CreatorApplication application = getApplication(applicationId);
        if (!application.getMemberId().equals(memberId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return application;
    }

    private void recordHistory(CreatorApplication application, String action, Long actorId, String reason) {
        reviewHistoryRepository.save(new CreatorApplicationReviewHistory(
                application.getId(), action, application.getSubmissionVersion(), actorId, reason));
    }

    private Member requireMember(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    private CreatorApplication getApplication(Long applicationId) {
        return applicationRepository.findById(applicationId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    private void requireAdmin(Long adminId) {
        Member admin = requireMember(adminId);
        if (admin.getRole() != MemberRole.ADMIN) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }

    public record ApplyResult(CreatorApplication application, boolean created) {
    }

    public record AdminApplication(CreatorApplication application, String applicantName) {
    }

    public record ApplicationDetail(CreatorApplication application, String applicantName,
                                    List<CreatorApplicationSubmission> submissions,
                                    List<CreatorApplicationReviewHistory> reviews) {
    }
}
