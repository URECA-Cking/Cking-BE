package kr.co.cking.creator.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
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

    /** Creator 신청을 같은 사용자의 중복 요청과 직렬화해 접수한다. */
    public ApplyResult apply(Long memberId) {
        return lockManager.execute("creator-application:member:" + memberId, () -> applyLocked(memberId));
    }

    /** 신청자 존재 여부와 현재 신청 상태를 확인한 뒤 새 PENDING 신청을 저장한다. */
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

    /** 요청 사용자가 낸 Creator 신청을 최신순 페이지로 조회한다. */
    @Transactional(readOnly = true)
    public Page<CreatorApplication> findMine(Long memberId, Pageable pageable) {
        requireMember(memberId);
        return applicationRepository.findByMemberIdOrderByRequestedAtDescIdDesc(memberId, pageable);
    }

    /** 관리자가 검토할 Creator 신청 목록에 신청자 이름을 조합해 반환한다. */
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

    /** Creator 승인 요청을 신청 건별 잠금 안에서 처리한다. */
    public CreatorApplication approve(Long adminId, Long applicationId) {
        return lockManager.execute("creator-application:review:" + applicationId,
                () -> approveLocked(adminId, applicationId));
    }

    /** 신청을 승인하고 Creator 및 기본 미션을 하나의 트랜잭션으로 생성한다. */
    private CreatorApplication approveLocked(Long adminId, Long applicationId) {
        requireAdmin(adminId);
        CreatorApplication application = getApplication(applicationId);
        Member applicant = requireMember(application.getMemberId());
        if (creatorRepository.existsByMemberId(application.getMemberId())) {
            throw new BusinessException(CreatorErrorCode.INVALID_STATE);
        }
        application.approve(adminId);
        kr.co.cking.creator.domain.Creator creator = creatorRepository.save(
                new kr.co.cking.creator.domain.Creator(application.getMemberId(), applicant.getName())
        );
        missionInitializationService.initializeDefaultMissions(creator.getCreatorId());
        return application;
    }

    /** Creator 거절 요청을 신청 건별 잠금 안에서 처리한다. */
    public CreatorApplication reject(Long adminId, Long applicationId, String rejectReason) {
        return lockManager.execute("creator-application:review:" + applicationId,
                () -> rejectLocked(adminId, applicationId, rejectReason));
    }

    /** 신청을 거절하고 검토자와 거절 사유를 기록한다. */
    private CreatorApplication rejectLocked(Long adminId, Long applicationId, String rejectReason) {
        requireAdmin(adminId);
        CreatorApplication application = getApplication(applicationId);
        application.reject(adminId, rejectReason.trim());
        return application;
    }

    /** 새 Creator 신청 엔티티를 저장한다. */
    private CreatorApplication saveApplication(Long memberId) {
        return applicationRepository.save(new CreatorApplication(memberId));
    }

    /** 지정한 Member를 조회하고 없으면 공통 리소스 없음 오류를 반환한다. */
    private Member requireMember(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    /** 지정한 Creator 신청을 조회하고 없으면 공통 리소스 없음 오류를 반환한다. */
    private CreatorApplication getApplication(Long applicationId) {
        return applicationRepository.findById(applicationId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    /** 요청 Member가 ADMIN 역할인지 검증한다. */
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
