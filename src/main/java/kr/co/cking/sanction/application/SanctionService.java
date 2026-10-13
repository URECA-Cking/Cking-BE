package kr.co.cking.sanction.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.sanction.application.dto.CommentHideSanctionResult;
import kr.co.cking.sanction.application.dto.SanctionView;
import kr.co.cking.sanction.domain.MemberSanction;
import kr.co.cking.sanction.domain.SanctionSource;
import kr.co.cking.sanction.repository.MemberSanctionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
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
        return new CommentHideSanctionResult(
                sanction.getSanctionId(), warningCount, sanction.getStartedAt(), sanction.getEndsAt(), true);
    }

    /** 관리자가 직접 댓글 작성 제한을 건다. 경고 횟수에는 세지 않는다. */
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
        return SanctionView.of(sanction, now);
    }

    /**
     * 유효한 제한을 지금 해제한다. 이미 끝났거나 해제된 제한을 다시 해제하면 아무것도 바꾸지 않고 성공한다. 자동 경고는
     * 해제해도 경고 횟수 집계에 남는다.
     */
    public SanctionView release(Long adminId, Long sanctionId) {
        memberQueryService.validateAdmin(adminId);
        MemberSanction sanction = sanctionRepository.findByIdForUpdate(sanctionId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        Instant now = clock.instant();
        sanction.release(adminId, now);
        return SanctionView.of(sanction, now);
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
