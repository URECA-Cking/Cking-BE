package kr.co.cking.sanction.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.exception.ErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.notification.application.SanctionNotificationService;
import kr.co.cking.sanction.application.dto.CommentHideSanctionResult;
import kr.co.cking.sanction.application.dto.SanctionView;
import kr.co.cking.sanction.domain.MemberSanction;
import kr.co.cking.sanction.domain.SanctionScope;
import kr.co.cking.sanction.domain.SanctionSource;
import kr.co.cking.sanction.domain.SanctionType;
import kr.co.cking.sanction.repository.MemberSanctionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;

class SanctionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");
    private static final Long MEMBER_ID = 20L;
    private static final Long ADMIN_ID = 1L;
    private static final Long COMMENT_ID = 500L;
    private static final Duration WINDOW = Duration.ofDays(90);

    private final MemberQueryService memberQueryService = mock(MemberQueryService.class);
    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final MemberSanctionRepository sanctionRepository = mock(MemberSanctionRepository.class);
    private final SanctionSettings settings = new SanctionSettings(
            WINDOW, List.of(Duration.ofDays(1), Duration.ofDays(3), Duration.ofDays(7)));
    private final SanctionNotificationService notificationService = mock(SanctionNotificationService.class);
    private final SanctionService service = new SanctionService(
            memberQueryService, memberRepository, sanctionRepository, settings, notificationService,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        given(sanctionRepository.save(any(MemberSanction.class))).willAnswer(invocation -> {
            MemberSanction saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "sanctionId", 900L);
            return saved;
        });
        given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(mock(Member.class)));
    }

    @Test
    void 첫_경고는_1일_두번째는_3일_세번째부터는_7일_제한이다() {
        assertThat(durationAfterPriorWarnings(0)).isEqualTo(Duration.ofDays(1));
        assertThat(durationAfterPriorWarnings(1)).isEqualTo(Duration.ofDays(3));
        assertThat(durationAfterPriorWarnings(2)).isEqualTo(Duration.ofDays(7));
        assertThat(durationAfterPriorWarnings(3)).isEqualTo(Duration.ofDays(7));
        assertThat(durationAfterPriorWarnings(10)).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void 경고는_집계_기간_시작_시각_이후의_자동_경고만_센다() {
        service.recordCommentHide(MEMBER_ID, COMMENT_ID, ADMIN_ID, NOW);

        // 90일 전 시각 이상(경계 포함)부터 센다. 그보다 오래된 경고는 집계에서 빠진다.
        verify(sanctionRepository).countByMemberIdAndSourceAndStartedAtGreaterThanEqual(
                MEMBER_ID, SanctionSource.AUTOMATIC, NOW.minus(WINDOW));
    }

    @Test
    void 경고를_기록하면_경고_횟수와_제한_구간을_돌려주고_작성자_회원의_자동_경고로_저장한다() {
        given(sanctionRepository.countByMemberIdAndSourceAndStartedAtGreaterThanEqual(
                MEMBER_ID, SanctionSource.AUTOMATIC, NOW.minus(WINDOW))).willReturn(1L);

        CommentHideSanctionResult result = service.recordCommentHide(MEMBER_ID, COMMENT_ID, ADMIN_ID, NOW);

        assertThat(result.warningCount()).isEqualTo(2);
        assertThat(result.newlyRecorded()).isTrue();
        assertThat(result.sanctionId()).isEqualTo(900L);
        assertThat(result.startedAt()).isEqualTo(NOW);
        assertThat(result.endsAt()).isEqualTo(NOW.plus(Duration.ofDays(3)));
        MemberSanction saved = savedSanction();
        assertThat(saved.getMemberId()).isEqualTo(MEMBER_ID);
        assertThat(saved.getCauseCommentId()).isEqualTo(COMMENT_ID);
        assertThat(saved.getCreatedBy()).isEqualTo(ADMIN_ID);
        assertThat(saved.getSource()).isEqualTo(SanctionSource.AUTOMATIC);
    }

    @Test
    void 같은_댓글로_이미_경고가_쌓였으면_새로_만들지_않는다() {
        MemberSanction existing = MemberSanction.automatic(MEMBER_ID, COMMENT_ID, ADMIN_ID, NOW.minusSeconds(60), Duration.ofDays(1));
        ReflectionTestUtils.setField(existing, "sanctionId", 777L);
        given(sanctionRepository.findByCauseCommentId(COMMENT_ID)).willReturn(Optional.of(existing));
        given(sanctionRepository.countByMemberIdAndSourceAndStartedAtGreaterThanEqual(
                MEMBER_ID, SanctionSource.AUTOMATIC, NOW.minus(WINDOW))).willReturn(1L);

        CommentHideSanctionResult result = service.recordCommentHide(MEMBER_ID, COMMENT_ID, ADMIN_ID, NOW);

        assertThat(result.newlyRecorded()).isFalse();
        assertThat(result.sanctionId()).isEqualTo(777L);
        assertThat(result.warningCount()).isEqualTo(1);
        verify(sanctionRepository, never()).save(any(MemberSanction.class));
    }

    @Test
    void 경고가_새로_쌓이면_경고_횟수와_해제_시각을_담은_제한_시작_알림을_만든다() {
        given(sanctionRepository.countByMemberIdAndSourceAndStartedAtGreaterThanEqual(
                MEMBER_ID, SanctionSource.AUTOMATIC, NOW.minus(WINDOW))).willReturn(1L);

        service.recordCommentHide(MEMBER_ID, COMMENT_ID, ADMIN_ID, NOW);

        verify(notificationService).createCommentRestrictionStartedByWarning(
                MEMBER_ID, 900L, 2, NOW.plus(Duration.ofDays(3)));
    }

    @Test
    void 기존에_더_오래가는_제한이_있으면_경고_알림은_그_종료_시각을_안내한다() {
        Instant existingEndsAt = NOW.plus(Duration.ofDays(30));
        given(sanctionRepository.findActiveEndsAt(
                MEMBER_ID, SanctionScope.PLATFORM, SanctionType.COMMENT_RESTRICTION, NOW))
                .willReturn(Optional.of(existingEndsAt));

        CommentHideSanctionResult result = service.recordCommentHide(MEMBER_ID, COMMENT_ID, ADMIN_ID, NOW);

        // 새 경고 제한은 1일이지만 실제 작성 제한은 30일까지 이어지므로 알림은 30일 뒤를 안내한다.
        verify(notificationService).createCommentRestrictionStartedByWarning(MEMBER_ID, 900L, 1, existingEndsAt);
        assertThat(result.endsAt()).isEqualTo(NOW.plus(Duration.ofDays(1)));
    }

    @Test
    void 기존에_더_오래가는_제한이_있으면_수동_제한_알림도_그_종료_시각을_안내한다() {
        Instant existingEndsAt = NOW.plus(Duration.ofDays(30));
        given(sanctionRepository.findActiveEndsAt(
                MEMBER_ID, SanctionScope.PLATFORM, SanctionType.COMMENT_RESTRICTION, NOW))
                .willReturn(Optional.of(existingEndsAt));

        service.createManual(ADMIN_ID, MEMBER_ID, 2, "사유");

        verify(notificationService).createCommentRestrictionStartedManually(MEMBER_ID, 900L, existingEndsAt);
    }

    @Test
    void 제한을_만드는_두_메서드는_동시에_커밋된_제한까지_읽도록_READ_COMMITTED로_실행한다() throws Exception {
        Transactional hide = SanctionService.class
                .getMethod("recordCommentHide", Long.class, Long.class, Long.class, Instant.class)
                .getAnnotation(Transactional.class);
        Transactional manual = SanctionService.class
                .getMethod("createManual", Long.class, Long.class, int.class, String.class)
                .getAnnotation(Transactional.class);

        assertThat(hide).isNotNull();
        assertThat(hide.isolation()).isEqualTo(Isolation.READ_COMMITTED);
        assertThat(manual).isNotNull();
        assertThat(manual.isolation()).isEqualTo(Isolation.READ_COMMITTED);
    }

    @Test
    void 같은_댓글로_이미_경고가_쌓였으면_알림도_다시_만들지_않는다() {
        MemberSanction existing = MemberSanction.automatic(MEMBER_ID, COMMENT_ID, ADMIN_ID, NOW.minusSeconds(60), Duration.ofDays(1));
        given(sanctionRepository.findByCauseCommentId(COMMENT_ID)).willReturn(Optional.of(existing));

        service.recordCommentHide(MEMBER_ID, COMMENT_ID, ADMIN_ID, NOW);

        verifyNoInteractions(notificationService);
    }

    @Test
    void 대상_회원을_잠그고_없으면_404다() {
        service.lockSubject(MEMBER_ID);

        verify(memberRepository).findByIdForUpdate(MEMBER_ID);
        assertError(() -> service.lockSubject(999L), CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 수동_제한은_경고_횟수에_세지_않는_수동_제한으로_저장한다() {
        SanctionView view = service.createManual(ADMIN_ID, MEMBER_ID, 5, "  반복 욕설  ");

        MemberSanction saved = savedSanction();
        assertThat(saved.getSource()).isEqualTo(SanctionSource.MANUAL);
        assertThat(saved.getReason()).isEqualTo("반복 욕설");
        assertThat(saved.getCauseCommentId()).isNull();
        assertThat(saved.getEndsAt()).isEqualTo(NOW.plus(Duration.ofDays(5)));
        assertThat(view.active()).isTrue();
        assertThat(view.source()).isEqualTo(SanctionSource.MANUAL);
        verify(memberQueryService).validateAdmin(ADMIN_ID);
        verify(memberRepository).findByIdForUpdate(MEMBER_ID);
        verify(notificationService).createCommentRestrictionStartedManually(
                MEMBER_ID, 900L, NOW.plus(Duration.ofDays(5)));
    }

    @Test
    void 수동_제한의_기간과_사유가_잘못되면_저장하지_않는다() {
        assertError(() -> service.createManual(ADMIN_ID, MEMBER_ID, 0, "사유"), CommonErrorCode.VALIDATION_FAILED);
        assertError(() -> service.createManual(ADMIN_ID, MEMBER_ID, SanctionService.MAX_MANUAL_DAYS + 1, "사유"),
                CommonErrorCode.VALIDATION_FAILED);
        assertError(() -> service.createManual(ADMIN_ID, MEMBER_ID, 1, " "), CommonErrorCode.VALIDATION_FAILED);
        assertError(() -> service.createManual(ADMIN_ID, MEMBER_ID, 1, null), CommonErrorCode.VALIDATION_FAILED);
        assertError(() -> service.createManual(ADMIN_ID, MEMBER_ID, 1, "가".repeat(201)), CommonErrorCode.VALIDATION_FAILED);
        verify(sanctionRepository, never()).save(any(MemberSanction.class));
        verifyNoInteractions(notificationService);
    }

    @Test
    void 없는_회원에게_수동_제한을_걸면_404다() {
        assertError(() -> service.createManual(ADMIN_ID, 999L, 1, "사유"), CommonErrorCode.RESOURCE_NOT_FOUND);
        verify(sanctionRepository, never()).save(any(MemberSanction.class));
    }

    @Test
    void 관리자가_아니면_수동_제한과_해제를_할_수_없다() {
        willThrow(new BusinessException(CommonErrorCode.FORBIDDEN)).given(memberQueryService).validateAdmin(7L);

        assertError(() -> service.createManual(7L, MEMBER_ID, 1, "사유"), CommonErrorCode.FORBIDDEN);
        assertError(() -> service.release(7L, 900L), CommonErrorCode.FORBIDDEN);
        verify(sanctionRepository, never()).save(any(MemberSanction.class));
        verify(sanctionRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void 해제하면_지금부터_무효이고_다시_해제해도_바뀌지_않는다() {
        MemberSanction sanction = MemberSanction.manual(MEMBER_ID, ADMIN_ID, "사유", NOW.minusSeconds(3600), Duration.ofDays(7));
        given(sanctionRepository.findByIdForUpdate(900L)).willReturn(Optional.of(sanction));

        SanctionView first = service.release(9L, 900L);
        SanctionView second = service.release(10L, 900L);

        assertThat(first.active()).isFalse();
        assertThat(first.releasedAt()).isEqualTo(NOW);
        assertThat(first.releasedBy()).isEqualTo(9L);
        assertThat(second.releasedBy()).isEqualTo(9L);
        // 남은 제한이 없을 때 첫 해제에서만 알린다.
        verify(notificationService).createCommentRestrictionReleased(MEMBER_ID, sanction.getSanctionId());
    }

    @Test
    void 다른_제한이_남아_있으면_해제해도_해제_알림을_보내지_않는다() {
        MemberSanction sanction = MemberSanction.manual(MEMBER_ID, ADMIN_ID, "사유", NOW.minusSeconds(3600), Duration.ofDays(7));
        given(sanctionRepository.findByIdForUpdate(900L)).willReturn(Optional.of(sanction));
        given(sanctionRepository.findActiveEndsAt(
                MEMBER_ID, SanctionScope.PLATFORM, SanctionType.COMMENT_RESTRICTION, NOW))
                .willReturn(Optional.of(NOW.plus(Duration.ofDays(1))));

        SanctionView view = service.release(ADMIN_ID, 900L);

        assertThat(view.active()).isFalse();
        verifyNoInteractions(notificationService);
    }

    @Test
    void 이미_끝났거나_해제된_제한을_해제하면_알림을_보내지_않는다() {
        MemberSanction expired = MemberSanction.manual(MEMBER_ID, ADMIN_ID, "사유", NOW.minus(Duration.ofDays(3)), Duration.ofDays(1));
        given(sanctionRepository.findByIdForUpdate(900L)).willReturn(Optional.of(expired));

        service.release(ADMIN_ID, 900L);

        verifyNoInteractions(notificationService);
    }

    @Test
    void 해제는_제재_행_다음_회원_행을_잠근_뒤_남은_제한을_읽고_READ_COMMITTED로_실행한다() throws Exception {
        MemberSanction sanction = MemberSanction.manual(MEMBER_ID, ADMIN_ID, "사유", NOW.minusSeconds(3600), Duration.ofDays(7));
        given(sanctionRepository.findByIdForUpdate(900L)).willReturn(Optional.of(sanction));

        service.release(ADMIN_ID, 900L);

        InOrder order = inOrder(sanctionRepository, memberRepository);
        order.verify(sanctionRepository).findByIdForUpdate(900L);
        order.verify(memberRepository).findByIdForUpdate(MEMBER_ID);
        order.verify(sanctionRepository).findActiveEndsAt(
                MEMBER_ID, SanctionScope.PLATFORM, SanctionType.COMMENT_RESTRICTION, NOW);
        Transactional transactional = SanctionService.class
                .getMethod("release", Long.class, Long.class).getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.isolation()).isEqualTo(Isolation.READ_COMMITTED);
    }

    @Test
    void 없는_제재를_해제하면_404다() {
        given(sanctionRepository.findByIdForUpdate(999L)).willReturn(Optional.empty());

        assertError(() -> service.release(ADMIN_ID, 999L), CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    private Duration durationAfterPriorWarnings(long prior) {
        given(sanctionRepository.countByMemberIdAndSourceAndStartedAtGreaterThanEqual(
                MEMBER_ID, SanctionSource.AUTOMATIC, NOW.minus(WINDOW))).willReturn(prior);
        CommentHideSanctionResult result = service.recordCommentHide(MEMBER_ID, COMMENT_ID + prior, ADMIN_ID, NOW);
        return Duration.between(result.startedAt(), result.endsAt());
    }

    private MemberSanction savedSanction() {
        ArgumentCaptor<MemberSanction> captor = ArgumentCaptor.forClass(MemberSanction.class);
        verify(sanctionRepository).save(captor.capture());
        return captor.getValue();
    }

    private void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, ErrorCode expected) {
        assertThatThrownBy(callable).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }
}
