package kr.co.cking.sanction.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.application.MemberInfo;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
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
 * 크리에이터가 자기 스페이스에서 회원의 댓글 작성·수정을 막고 푼다(이슈 #579). 관리자는 크리에이터를 지정해 어느 스페이스에서든
 * 같은 차단을 걸고 풀 수 있다(이슈 #584). 호출 주체(actor)만 다르고 검증·알림 규칙은 같다. 잠금은 호출 주체에 따라 다르다
 * ({@link #lockMembers} 참고).
 *
 * <p>차단은 {@code member_sanction}의 {@link SanctionScope#SPACE} 행이다. 그 스페이스에서만 막고, 경고 횟수에 세지 않으며
 * 플랫폼 제재 단계에도 영향을 주지 않는다. 기간제(1~365일)이고 영구 차단은 없다. 같은 크리에이터가 같은 회원에게 이미
 * 유효한 차단이 있으면 새로 만들지 않고 기존 차단을 그대로 돌려준다(기간을 바꾸려면 해제 후 다시 건다).
 *
 * <p>크리에이터가 차단·해제하면 대상 회원과 크리에이터의 회원 행을 <b>회원 ID 오름차순으로 둘 다</b> 쓰기 잠금으로 먼저 읽는다.
 * 제재 INSERT·해제 UPDATE는 {@code created_by}·{@code released_by} FK 검사로 크리에이터 회원 행의 공유 잠금이 필요한데(READ_COMMITTED에서도
 * 유지된다), 대상 행만 잠그면 크리에이터 A가 B를, B가 A를 동시에 차단할 때 서로 상대 행을 기다리는 순환 대기가 생긴다. 두 행을
 * 같은 순서로 잠그면 한쪽이 먼저 끝날 때까지 기다리므로 교착이 없다. 관리자가 차단·해제하면 대상 행만 잠가 플랫폼 제재 경로와
 * 순서를 맞춘다. 같은 회원에 대한 중복 차단과 댓글 작성(역시 회원 행을 먼저 잠근다)도 이 잠금으로 직렬화된다. 해제는 제재 행 →
 * 회원 행 순서이며, 변경 메서드는 {@code READ_COMMITTED}로 실행해 유효한 차단을 읽는 쿼리가 동시에 커밋된 변경을 보게 한다.
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
    private final MemberRepository memberRepository;
    private final SanctionNotificationService notificationService;
    private final Clock clock;

    /**
     * 호출한 크리에이터의 스페이스에서 {@code targetMemberId}의 댓글 작성·수정을 지금부터 {@code days}일 막는다. 이미 유효한
     * 차단이 있으면 새로 만들지 않고 기존 차단을 돌려준다.
     *
     * @throws BusinessException Creator가 아니면 {@code FORBIDDEN}, 기간이 범위를 벗어나면 {@code VALIDATION_FAILED},
     *                           자기 자신이면 {@code CANNOT_BLOCK_SELF}, 대상 회원이 없으면 {@code RESOURCE_NOT_FOUND},
     *                           대상이 관리자면 {@code CANNOT_BLOCK_ADMIN}
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SpaceCommentBlockView block(Long creatorMemberId, Long targetMemberId, int days) {
        Creator creator = requireCreator(creatorMemberId);
        return createBlock(creator, creatorMemberId, true, targetMemberId, days);
    }

    /**
     * 관리자가 {@code creatorId}의 스페이스에서 {@code targetMemberId}의 댓글 작성·수정을 지금부터 {@code days}일 막는다. 규칙은
     * 크리에이터의 차단과 같고, 같은 (크리에이터, 회원) 쌍에 이미 유효한 차단이 있으면(누가 걸었든) 새로 만들지 않고 기존 차단을
     * 돌려준다. 스페이스 주인 본인은 자기 스페이스에서 차단할 수 없다({@code CANNOT_BLOCK_SPACE_OWNER}).
     *
     * @throws BusinessException 관리자가 아니면 {@code FORBIDDEN}, 크리에이터가 없으면 {@code RESOURCE_NOT_FOUND}
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SpaceCommentBlockView blockByAdmin(Long adminId, Long creatorId, Long targetMemberId, int days) {
        memberQueryService.validateAdmin(adminId);
        Creator creator = creatorRepository.findById(creatorId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        return createBlock(creator, adminId, false, targetMemberId, days);
    }

    /** {@code lockActorRow}는 호출 주체가 크리에이터면 true, 관리자면 false다({@link #lockMembers} 참고). */
    private SpaceCommentBlockView createBlock(
            Creator creator, Long actorMemberId, boolean lockActorRow, Long targetMemberId, int days) {
        if (targetMemberId == null || days < 1 || days > MAX_DAYS) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        if (actorMemberId.equals(targetMemberId)) {
            throw new BusinessException(SanctionErrorCode.CANNOT_BLOCK_SELF);
        }
        if (creator.getMemberId().equals(targetMemberId)) {
            throw new BusinessException(SanctionErrorCode.CANNOT_BLOCK_SPACE_OWNER);
        }
        Member target = lockMembers(actorMemberId, lockActorRow, targetMemberId);
        if (target.getRole() == MemberRole.ADMIN) {
            throw new BusinessException(SanctionErrorCode.CANNOT_BLOCK_ADMIN);
        }
        Instant now = clock.instant();
        List<MemberSanction> active = sanctionRepository.findActiveSpaceBlocks(creator.getCreatorId(), targetMemberId, now);
        if (!active.isEmpty()) {
            return view(active.get(0), now);
        }
        MemberSanction block = sanctionRepository.save(MemberSanction.spaceBlock(
                targetMemberId, creator.getCreatorId(), actorMemberId, now, Duration.ofDays(days)));
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
        return releaseLocked(block, creator, creatorMemberId, true);
    }

    /**
     * 관리자가 어느 스페이스의 차단이든(크리에이터가 걸었든 관리자가 걸었든) 지금 푼다. 이미 끝났거나 해제된 차단을 다시 풀면
     * 아무것도 바꾸지 않고 성공한다. 스페이스 차단이 아닌 제재(플랫폼 제재)는 {@code RESOURCE_NOT_FOUND}다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SpaceCommentBlockView releaseByAdmin(Long adminId, Long sanctionId) {
        memberQueryService.validateAdmin(adminId);
        MemberSanction block = sanctionRepository.findByIdForUpdate(sanctionId)
                .filter(found -> found.getScope() == SanctionScope.SPACE)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        Creator creator = creatorRepository.findById(block.getSpaceCreatorId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        return releaseLocked(block, creator, adminId, false);
    }

    private SpaceCommentBlockView releaseLocked(
            MemberSanction block, Creator creator, Long releasedByMemberId, boolean lockActorRow) {
        lockMembers(releasedByMemberId, lockActorRow, block.getMemberId());
        Instant now = clock.instant();
        boolean wasActive = block.isActiveAt(now);
        block.release(releasedByMemberId, now);
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

    /**
     * 관리자가 모든 스페이스의 차단을 최신 시작 순으로 조회한다. {@code creatorId}·{@code memberId}를 주면 그 스페이스·그 회원의
     * 차단만, {@code activeOnly}면 지금 유효한 차단만 돌려준다.
     */
    @Transactional(readOnly = true)
    public Page<SpaceCommentBlockView> findBlocksByAdmin(
            Long adminId, Long creatorId, Long memberId, boolean activeOnly, Pageable pageable) {
        memberQueryService.validateAdmin(adminId);
        Instant now = clock.instant();
        Page<MemberSanction> blocks = sanctionRepository.searchSpaceBlocksForAdmin(
                creatorId, memberId, activeOnly, now, pageable);
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

    /**
     * 대상 회원 행을 쓰기 잠금으로 읽고 돌려준다. 호출 주체가 <b>크리에이터</b>이면 크리에이터의 회원 행도 함께 잠그되, 두 행을
     * 회원 ID 오름차순으로 잠가 서로를 동시에 차단·해제해도 FK 공유 잠금 때문에 순환 대기가 생기지 않게 한다. 호출 주체가
     * <b>관리자</b>이면 대상 행만 잠근다. 관리자는 스페이스 차단의 대상이 될 수 없어 다른 차단이 관리자 행을 쓰기 잠금으로
     * 잡지 않고, 플랫폼 제재({@link SanctionService})도 대상 행을 먼저 잠근 뒤 {@code created_by}·{@code released_by} FK로
     * 관리자 행의 공유 잠금만 요구한다. 관리자 행을 먼저 쓰기 잠금으로 잡으면 같은 관리자가 같은 대상에게 보낸 플랫폼 제한과
     * 서로의 잠금을 기다리는 교착이 생기므로, 잠금 순서(대상 → 관리자 FK)를 기존 제재 경로와 맞춘다. 대상 회원이 없으면 404다.
     */
    private Member lockMembers(Long actorMemberId, boolean lockActorRow, Long targetMemberId) {
        Member target = null;
        List<Long> inLockOrder = !lockActorRow ? List.of(targetMemberId)
                : actorMemberId < targetMemberId
                        ? List.of(actorMemberId, targetMemberId) : List.of(targetMemberId, actorMemberId);
        for (Long memberId : inLockOrder) {
            Member locked = memberRepository.findByIdForUpdate(memberId)
                    .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
            if (memberId.equals(targetMemberId)) {
                target = locked;
            }
        }
        return target;
    }

    /** 호출자가 Creator가 아니면 403이다. */
    private Creator requireCreator(Long memberId) {
        return creatorRepository.findByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.FORBIDDEN));
    }
}
