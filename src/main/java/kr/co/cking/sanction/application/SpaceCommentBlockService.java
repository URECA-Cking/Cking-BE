package kr.co.cking.sanction.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.application.MemberInfo;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.notification.application.SanctionNotificationService;
import kr.co.cking.sanction.application.dto.SpaceCommentBlockView;
import kr.co.cking.sanction.domain.MemberSanction;
import kr.co.cking.sanction.domain.SanctionErrorCode;
import kr.co.cking.sanction.domain.SanctionScope;
import kr.co.cking.sanction.repository.MemberSanctionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 크리에이터가 자기 스페이스에서 회원의 댓글 작성·수정을 막고 푼다(이슈 #579).
 *
 * <p>차단은 {@code member_sanction}의 {@link SanctionScope#SPACE} 행이다. 그 스페이스에서만 막고, 경고 횟수에 세지 않으며
 * 플랫폼 제재 단계에도 영향을 주지 않는다. 기간제(1~365일)이고 영구 차단은 없다. 같은 크리에이터가 같은 회원에게 이미
 * 유효한 차단이 있으면 새로 만들지 않고 기존 차단을 그대로 돌려준다(기간을 바꾸려면 해제 후 다시 건다).
 *
 * <p>잠금은 {@link SanctionService}와 같은 규칙이다. 차단은 대상 회원 행을 먼저 쓰기 잠금으로 읽어 같은 회원에 대한 중복
 * 차단과 댓글 작성(역시 회원 행을 먼저 잠근다)을 직렬화하고, 해제는 제재 행 → 회원 행 순서로 잠근다. 유효한 차단을 읽는
 * 쿼리가 동시에 커밋된 변경을 보도록 변경 메서드는 {@code READ_COMMITTED}로 실행한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SpaceCommentBlockService {

    /** 차단 기간의 상한(일). 영구 차단은 이 이슈의 범위가 아니다. */
    public static final int MAX_DAYS = 365;

    private final CreatorRepository creatorRepository;
    private final MemberQueryService memberQueryService;
    private final MemberSanctionRepository sanctionRepository;
    private final SanctionService sanctionService;
    private final SanctionNotificationService notificationService;
    private final Clock clock;

    /**
     * 호출한 크리에이터의 스페이스에서 {@code targetMemberId}의 댓글 작성·수정을 지금부터 {@code days}일 막는다. 이미 유효한
     * 차단이 있으면 새로 만들지 않고 기존 차단을 돌려준다.
     *
     * @throws BusinessException Creator가 아니면 {@code FORBIDDEN}, 기간이 범위를 벗어나면 {@code VALIDATION_FAILED},
     *                           자기 자신이면 {@code CANNOT_BLOCK_SELF}, 대상 회원이 없으면 {@code RESOURCE_NOT_FOUND}
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SpaceCommentBlockView block(Long creatorMemberId, Long targetMemberId, int days) {
        Creator creator = requireCreator(creatorMemberId);
        if (targetMemberId == null || days < 1 || days > MAX_DAYS) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        if (creatorMemberId.equals(targetMemberId)) {
            throw new BusinessException(SanctionErrorCode.CANNOT_BLOCK_SELF);
        }
        sanctionService.lockSubject(targetMemberId);
        Instant now = clock.instant();
        List<MemberSanction> active = sanctionRepository.findActiveSpaceBlocks(creator.getCreatorId(), targetMemberId, now);
        if (!active.isEmpty()) {
            return view(active.get(0), now);
        }
        MemberSanction block = sanctionRepository.save(MemberSanction.spaceBlock(
                targetMemberId, creator.getCreatorId(), creatorMemberId, now, Duration.ofDays(days)));
        notificationService.createSpaceCommentBlockStarted(
                targetMemberId, block.getSanctionId(), creator.publicDisplayName(), block.getEndsAt());
        return view(block, now);
    }

    /**
     * 호출한 크리에이터가 건 차단을 지금 푼다. 이미 끝났거나 해제된 차단을 다시 풀면 아무것도 바꾸지 않고 성공한다. 다른
     * 크리에이터의 차단이나 플랫폼 제재는 존재 여부를 드러내지 않도록 {@code RESOURCE_NOT_FOUND}다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SpaceCommentBlockView release(Long creatorMemberId, Long sanctionId) {
        Creator creator = requireCreator(creatorMemberId);
        MemberSanction block = sanctionRepository.findByIdForUpdate(sanctionId)
                .filter(found -> found.getScope() == SanctionScope.SPACE)
                .filter(found -> creator.getCreatorId().equals(found.getSpaceCreatorId()))
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        sanctionService.lockSubject(block.getMemberId());
        Instant now = clock.instant();
        boolean wasActive = block.isActiveAt(now);
        block.release(creatorMemberId, now);
        if (wasActive) {
            notificationService.createSpaceCommentBlockReleased(
                    block.getMemberId(), block.getSanctionId(), creator.publicDisplayName());
        }
        return view(block, now);
    }

    /** 호출한 크리에이터가 건 차단을 최신 시작 순으로 조회한다. {@code activeOnly}면 지금 유효한 차단만 돌려준다. */
    @Transactional(readOnly = true)
    public Page<SpaceCommentBlockView> findBlocks(Long creatorMemberId, boolean activeOnly, Pageable pageable) {
        Creator creator = requireCreator(creatorMemberId);
        Instant now = clock.instant();
        Page<MemberSanction> blocks = sanctionRepository.searchSpaceBlocks(
                creator.getCreatorId(), activeOnly, now, pageable);
        Set<Long> memberIds = blocks.getContent().stream().map(MemberSanction::getMemberId).collect(Collectors.toSet());
        Map<Long, MemberInfo> infos = memberQueryService.findMemberInfosByIds(memberIds);
        return blocks.map(block -> SpaceCommentBlockView.of(block, nameOf(infos, block.getMemberId()), now));
    }

    private SpaceCommentBlockView view(MemberSanction block, Instant now) {
        Map<Long, MemberInfo> infos = memberQueryService.findMemberInfosByIds(Set.of(block.getMemberId()));
        return SpaceCommentBlockView.of(block, nameOf(infos, block.getMemberId()), now);
    }

    private String nameOf(Map<Long, MemberInfo> infos, Long memberId) {
        MemberInfo info = infos.get(memberId);
        return info == null ? null : info.name();
    }

    /** 호출자가 Creator가 아니면 403이다. */
    private Creator requireCreator(Long memberId) {
        return creatorRepository.findByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.FORBIDDEN));
    }
}
