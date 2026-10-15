package kr.co.cking.sanction.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.notification.application.SanctionNotificationService;
import kr.co.cking.sanction.application.dto.CommentHideSanctionResult;
import kr.co.cking.sanction.application.dto.SanctionView;
import kr.co.cking.sanction.domain.MemberSanction;
import kr.co.cking.sanction.domain.SanctionScope;
import kr.co.cking.sanction.domain.SanctionSource;
import kr.co.cking.sanction.domain.SanctionType;
import kr.co.cking.sanction.repository.MemberSanctionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 제재를 만들고 해제한다(이슈 #569).
 *
 * <p>관리자가 신고된 댓글을 숨기면({@link #recordCommentHide}) 작성자에게 경고가 쌓이고, 집계 기간(기본 90일) 안의 경고
 * 횟수에 따라 댓글 작성 제한 기간이 정해진다(기본 1회 1일, 2회 3일, 3회 이상 7일). 오래된 경고는 집계에서만 빠지고 기록은
 * 지우지 않는다. 제한이 겹쳐도 새로 합치지 않고 각각 기록하며, 작성 가드는 유효한 제한 중 가장 늦게 끝나는 시각을 쓴다.
 *
 * <p>경고 횟수를 읽고 새 경고를 저장하는 두 단계라, 같은 회원의 서로 다른 댓글이 동시에 숨겨지면 둘 다 같은 횟수를 읽을 수
 * 있다. 그래서 호출자는 먼저 {@link #lockSubject}로 대상 회원 행을 쓰기 잠금으로 읽어 같은 회원의 처리를 직렬화한다. 잠금
 * 순서는 회원 → 게시글 → 댓글로 댓글 신고 접수와 같다.
 *
 * <p>제한이 시작되거나(자동 경고, 수동 제한) 모두 풀리면(해제) 같은 Transaction에서 작성자에게 알림을 만든다(이슈 #572).
 * 알림은 제재 저장과 함께 커밋되거나 롤백되며, 같은 제재의 같은 종류 알림은 한 번만 생긴다.
 *
 * <p>제한 시작 알림에 담는 해제 시각은 방금 만든 제재 하나의 종료 시각이 아니라 <b>지금 유효한 제한 중 가장 늦게 끝나는
 * 시각</b>이다. 제한이 겹치면 작성 가드가 가장 늦은 시각을 쓰므로, 30일 수동 제한이 있는 회원에게 1일 경고가 붙어도
 * 알림은 30일 뒤를 안내해야 실제와 맞는다. 이 값을 읽을 때 동시에 커밋된 다른 제한까지 보도록 제한을 만드는 두 메서드는
 * {@code READ_COMMITTED}로 실행한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SanctionService {

    /** 수동 제한 기간의 상한(일). 영구 제한은 이 이슈의 범위가 아니다. */
    public static final int MAX_MANUAL_DAYS = 365;

    private final MemberQueryService memberQueryService;
    private final MemberRepository memberRepository;
    private final MemberSanctionRepository sanctionRepository;
    private final SanctionSettings settings;
    private final SanctionNotificationService notificationService;
    private final Clock clock;

    /** 대상 회원 행을 쓰기 잠금으로 읽어 같은 회원에 대한 제재 처리를 직렬화한다. 회원이 없으면 404다. */
    public void lockSubject(Long memberId) {
        memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    /**
     * 관리자가 댓글을 숨긴 것을 경고로 기록하고 경고 횟수에 맞는 댓글 작성 제한을 만든다. 같은 댓글로 이미 경고가 쌓였으면
     * 아무것도 만들지 않고 기존 결과를 돌려준다. 호출자가 {@link #lockSubject}를 먼저 호출해야 한다.
     *
     * @param now 경고 시각. 집계 기간과 제한 시작 시각의 기준이다
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CommentHideSanctionResult recordCommentHide(Long memberId, Long causeCommentId, Long adminId, Instant now) {
        Optional<MemberSanction> existing = sanctionRepository.findByCauseCommentId(causeCommentId);
        if (existing.isPresent()) {
            MemberSanction sanction = existing.get();
            return new CommentHideSanctionResult(
                    sanction.getSanctionId(), countWarnings(memberId, now), sanction.getStartedAt(), sanction.getEndsAt(), false);
        }
        long warningCount = countWarnings(memberId, now) + 1;
        Duration duration = settings.commentRestrictionFor(warningCount);
        MemberSanction sanction = sanctionRepository.save(
                MemberSanction.automatic(memberId, causeCommentId, adminId, now, duration));
        notificationService.createCommentRestrictionStartedByWarning(
                memberId, sanction.getSanctionId(), warningCount, restrictedUntil(memberId, now, sanction.getEndsAt()));
        return new CommentHideSanctionResult(
                sanction.getSanctionId(), warningCount, sanction.getStartedAt(), sanction.getEndsAt(), true);
    }

    /** 관리자가 직접 댓글 작성 제한을 건다. 경고 횟수에는 세지 않는다. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SanctionView createManual(Long adminId, Long memberId, int days, String reason) {
        memberQueryService.validateAdmin(adminId);
        String normalizedReason = validateReason(reason);
        if (days < 1 || days > MAX_MANUAL_DAYS) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        lockSubject(memberId);
        Instant now = clock.instant();
        MemberSanction sanction = sanctionRepository.save(
                MemberSanction.manual(memberId, adminId, normalizedReason, now, Duration.ofDays(days)));
        notificationService.createCommentRestrictionStartedManually(
                memberId, sanction.getSanctionId(), restrictedUntil(memberId, now, sanction.getEndsAt()));
        return SanctionView.of(sanction, now);
    }

    /**
     * 유효한 제한을 지금 해제한다. 이미 끝났거나 해제된 제한을 다시 해제하면 아무것도 바꾸지 않고 성공한다. 자동 경고는
     * 해제해도 경고 횟수 집계에 남는다. 이번 해제로 그 회원의 유효한 제한이 모두 없어졌을 때만 해제 알림을 만든다(다른
     * 제한이 남아 있으면 여전히 제한 중이므로 알리지 않는다).
     *
     * <p>남은 제한 여부를 읽기 전에 대상 회원 행을 잠그고 {@code READ_COMMITTED}로 실행한다. 같은 회원의 겹친 두 제한을
     * 동시에 해제할 때 서로 상대가 아직 유효하다고 읽어 둘 다 알리지 않는 일을 막는다. 잠금 순서는 제재 행 → 회원 행이며
     * 제재 행을 잠그는 흐름은 해제뿐이라 교착이 생기지 않는다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SanctionView release(Long adminId, Long sanctionId) {
        memberQueryService.validateAdmin(adminId);
        MemberSanction sanction = sanctionRepository.findByIdForUpdate(sanctionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        // 스페이스 차단(이슈 #579)은 그 스페이스의 크리에이터가 SpaceCommentBlockService로만 해제한다.
        if (sanction.getScope() != SanctionScope.PLATFORM) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
        lockSubject(sanction.getMemberId());
        Instant now = clock.instant();
        boolean wasActive = sanction.isActiveAt(now);
        sanction.release(adminId, now);
        if (wasActive && sanctionRepository.findActiveEndsAt(
                sanction.getMemberId(), SanctionScope.PLATFORM, SanctionType.COMMENT_RESTRICTION, now).isEmpty()) {
            notificationService.createCommentRestrictionReleased(sanction.getMemberId(), sanction.getSanctionId());
        }
        return SanctionView.of(sanction, now);
    }

    /**
     * 방금 저장한 제재를 포함해 지금 유효한 댓글 작성 제한 중 가장 늦게 끝나는 시각. 작성 가드가 적용하는 시각과 같다.
     * 조회 결과가 비는 일은 없지만(방금 만든 제재가 유효하다) 방어적으로 그 제재의 종료 시각을 대신 쓴다.
     */
    private Instant restrictedUntil(Long memberId, Instant now, Instant fallback) {
        return sanctionRepository.findActiveEndsAt(
                memberId, SanctionScope.PLATFORM, SanctionType.COMMENT_RESTRICTION, now).orElse(fallback);
    }

    private long countWarnings(Long memberId, Instant now) {
        return sanctionRepository.countByMemberIdAndSourceAndStartedAtGreaterThanEqual(
                memberId, SanctionSource.AUTOMATIC, now.minus(settings.warningWindow()));
    }

    private String validateReason(String reason) {
        if (reason == null || reason.isBlank() || reason.strip().length() > MemberSanction.MAX_REASON_LENGTH) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        return reason.strip();
    }
}
