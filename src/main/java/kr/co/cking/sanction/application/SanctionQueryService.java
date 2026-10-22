package kr.co.cking.sanction.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.sanction.application.dto.SanctionView;
import kr.co.cking.sanction.domain.SanctionErrorCode;
import kr.co.cking.sanction.domain.SanctionScope;
import kr.co.cking.sanction.domain.SanctionType;
import kr.co.cking.sanction.repository.MemberSanctionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/** 제재 조회와 댓글 작성 가드(이슈 #569). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SanctionQueryService {

    private final MemberQueryService memberQueryService;
    private final MemberSanctionRepository sanctionRepository;
    private final Clock clock;

    /**
     * 댓글 작성·수정 전에 호출한다. 지금 유효한 플랫폼 댓글 작성 제한이 있으면 {@code COMMENT_RESTRICTED}(403)이고, 메시지에
     * 가장 늦게 끝나는 제한의 해제 시각(UTC)을 담는다. 그 스페이스의 주인이 건 차단이 유효하면 {@code SPACE_COMMENT_BLOCKED}
     * (403)이며, 다른 스페이스에서는 쓸 수 있다. 둘 다 있으면 플랫폼 제한을 먼저 알린다. 삭제에는 쓰지 않는다.
     *
     * <p>호출자는 같은 트랜잭션에서 작성자(회원) 행을 쓰기 잠금으로 먼저 읽은 뒤 호출해야 한다. REPEATABLE READ에서는
     * 첫 일반 조회 시점의 스냅샷이 고정되므로, 잠금 없이 호출하면 그 사이 확정된 제재를 못 볼 수 있다.
     *
     * @param creatorId 댓글을 쓰려는 게시글이 속한 스페이스의 주인
     */
    public void requireCommentWriteAllowed(Long memberId, Long creatorId) {
        Instant now = clock.instant();
        sanctionRepository.findActiveEndsAt(memberId, SanctionScope.PLATFORM, SanctionType.COMMENT_RESTRICTION, now)
                .ifPresent(endsAt -> {
                    throw new BusinessException(
                            SanctionErrorCode.COMMENT_RESTRICTED,
                            "댓글 작성이 제한되었습니다. 해제 시각(UTC): " + endsAt);
                });
        sanctionRepository.findActiveSpaceBlockEndsAt(creatorId, memberId, now)
                .ifPresent(endsAt -> {
                    throw new BusinessException(
                            SanctionErrorCode.SPACE_COMMENT_BLOCKED,
                            "이 스페이스에서 댓글 작성이 제한되었습니다. 해제 시각(UTC): " + endsAt);
                });
    }

    /** 관리자용 제재 목록. 최신 시작 순이다. */
    public Page<SanctionView> findSanctions(Long adminId, Long memberId, boolean activeOnly, Pageable pageable) {
        memberQueryService.validateAdmin(adminId);
        Instant now = clock.instant();
        return sanctionRepository.search(memberId, activeOnly, now, pageable)
                .map(sanction -> SanctionView.of(sanction, now));
    }
}
