package kr.co.cking.creator.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorApplication;
import kr.co.cking.creator.domain.CreatorApplicationStatus;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.repository.CreatorApplicationRepository;
import kr.co.cking.creator.repository.CreatorRepository;
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

    public ApplyResult apply(Long memberId) {
        return lockManager.execute("creator-application:member:" + memberId, () -> applyLocked(memberId));
    }

    private ApplyResult applyLocked(Long memberId) {
        // 신청 처리에는 Member의 상세 정보가 필요 없으므로 존재 여부만 확인한다.
        if (!memberRepository.existsById(memberId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        if (creatorRepository.existsByMemberId(memberId)) {
            throw new BusinessException(CreatorErrorCode.INVALID_STATE);
        }
        // 처리 중인 신청은 새로 만들지 않고 기존 결과를 반환한다.
        Optional<CreatorApplication> existing = applicationRepository.findFirstByMemberIdAndStatusOrderByIdDesc(
                        memberId,
                        CreatorApplicationStatus.PENDING
                );
        if (existing.isPresent()) {
            return new ApplyResult(existing.get(), false);
        }
        return new ApplyResult(saveApplication(memberId), true);
    }

    @Transactional(readOnly = true)
    public Page<CreatorApplication> findMine(Long memberId, Pageable pageable) {
        requireMember(memberId);
        return applicationRepository.findByMemberIdOrderByRequestedAtDescIdDesc(memberId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<AdminApplication> findAllForAdmin(Long adminId, Pageable pageable) {
        requireAdmin(adminId);
        Page<CreatorApplication> applications = applicationRepository.findAllByOrderByRequestedAtAscIdAsc(pageable);
        Map<Long, String> applicantNames = memberRepository.findByMemberIdIn(applications.stream()
                        .map(CreatorApplication::getMemberId).toList())
                .stream().collect(Collectors.toMap(Member::getMemberId, Member::getName));
        List<AdminApplication> items = applications.stream()
                .map(application -> new AdminApplication(application, applicantNames.get(application.getMemberId())))
                .toList();
        return new org.springframework.data.domain.PageImpl<>(items, pageable, applications.getTotalElements());
    }

    public CreatorApplication approve(Long adminId, Long applicationId) {
        return lockManager.execute("creator-application:review:" + applicationId,
                () -> approveLocked(adminId, applicationId));
    }

    /** 신청을 승인하고 Creator·기본 미션·Creator Space를 하나의 트랜잭션으로 생성한다. */
    private CreatorApplication approveLocked(Long adminId, Long applicationId) {
        requireAdmin(adminId);
        CreatorApplication application = getApplication(applicationId);
        Member applicant = requireMember(application.getMemberId());
        if (creatorRepository.existsByMemberId(application.getMemberId())) {
            throw new BusinessException(CreatorErrorCode.INVALID_STATE);
        }
        application.approve(adminId);
        Creator creator = creatorRepository.save(
                new Creator(application.getMemberId(), applicant.getName())
        );
        missionInitializationService.initializeDefaultMissions(creator.getCreatorId());
        creatorSpaceService.createFromActiveTemplateIfAbsent(creator.getCreatorId());
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
        return application;
    }

    private CreatorApplication saveApplication(Long memberId) {
        return applicationRepository.save(new CreatorApplication(memberId));
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
}
