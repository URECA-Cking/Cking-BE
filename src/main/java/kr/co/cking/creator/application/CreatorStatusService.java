package kr.co.cking.creator.application;

import java.util.UUID;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.domain.CreatorStatus;
import kr.co.cking.creator.domain.CreatorStatusHistory;
import kr.co.cking.creator.domain.CreatorStatusReasonCode;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorStatusHistoryRepository;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

/** Creator 자격 상태 변경과 감사 이력의 단일 쓰기 경계. */
@Service
@RequiredArgsConstructor
public class CreatorStatusService {
    private final CreatorRepository creators;
    private final CreatorStatusHistoryRepository histories;
    private final MemberRepository members;
    private final CreatorApplicationLockManager lockManager;

    @Value("${cking.creator.status-management.enabled:false}")
    private boolean statusManagementEnabled;

    @Transactional
    public CreatorStatusHistory change(Long adminId, Long creatorId, CreatorStatus next,
                                       StatusCommand command) {
        if (!statusManagementEnabled) {
            throw new BusinessException(CreatorErrorCode.CREATOR_STATUS_MANAGEMENT_DISABLED);
        }
        validate(command);
        return lockManager.executeWaiting("creator-status-request:" + command.requestId(), 15,
                () -> changeLocked(adminId, creatorId, next, command));
    }

    private CreatorStatusHistory changeLocked(Long adminId, Long creatorId, CreatorStatus next,
                                              StatusCommand command) {
        requireAdmin(adminId);
        String requestId = command.requestId().toString();
        var replay = histories.findByRequestId(requestId);
        if (replay.isPresent()) {
            CreatorStatusHistory prior = replay.get();
            if (!prior.matches(creatorId, next, command.reasonCode().name(), command.detailReason().trim(),
                    command.publicNotice().trim(), adminId)) {
                throw new BusinessException(CreatorErrorCode.CREATOR_STATUS_REQUEST_CONFLICT);
            }
            return prior;
        }
        return lockManager.executeWaiting(CreatorRewardAdmissionService.lockKey(creatorId), 15, () -> {
            Creator creator = creators.findByIdForUpdate(creatorId)
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
            CreatorStatus previous = creator.getStatus();
            creator.changeStatus(next, command.publicNotice().trim());
            return histories.save(new CreatorStatusHistory(creatorId, previous, next, command.reasonCode().name(),
                    command.detailReason().trim(), command.publicNotice().trim(), adminId, requestId));
        });
    }

    @Transactional(readOnly = true)
    public Page<Creator> findAll(Long adminId, CreatorStatus status, Pageable pageable) {
        requireAdmin(adminId);
        return status == null ? creators.findAllByOrderByCreatorIdAsc(pageable)
                : creators.findByStatusOrderByCreatorIdAsc(status, pageable);
    }

    @Transactional(readOnly = true)
    public Creator findOne(Long adminId, Long creatorId) {
        requireAdmin(adminId);
        return creators.findById(creatorId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public Page<CreatorStatusHistory> findHistory(Long adminId, Long creatorId, Pageable pageable) {
        requireAdmin(adminId);
        if (!creators.existsById(creatorId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        return histories.findByCreatorIdOrderByIdDesc(creatorId, pageable);
    }

    @Transactional(readOnly = true)
    public Creator findMine(Long memberId) {
        return creators.findByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.FORBIDDEN));
    }

    private void requireAdmin(Long adminId) {
        if (members.findById(adminId).filter(member -> member.getRole() == MemberRole.ADMIN).isEmpty()) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }

    private void validate(StatusCommand command) {
        if (command == null || command.requestId() == null || command.reasonCode() == null
                || command.detailReason() == null || command.detailReason().isBlank()
                || command.detailReason().length() > 1000 || command.publicNotice() == null
                || command.publicNotice().isBlank() || command.publicNotice().length() > 500) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    public record StatusCommand(UUID requestId, CreatorStatusReasonCode reasonCode,
                                String detailReason, String publicNotice) {
    }
}
