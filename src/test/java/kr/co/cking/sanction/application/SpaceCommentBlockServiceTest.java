package kr.co.cking.sanction.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.exception.ErrorCode;
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
import kr.co.cking.sanction.domain.SanctionSource;
import kr.co.cking.sanction.domain.SanctionType;
import kr.co.cking.sanction.repository.MemberSanctionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SpaceCommentBlockServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
    private static final Long ADMIN_ID = 1L;
    private static final Long CREATOR_MEMBER_ID = 10L;
    private static final Long CREATOR_ID = 5L;
    private static final Long TARGET_ID = 20L;
    private static final Pageable PAGE = PageRequest.of(0, 20);

    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final MemberQueryService memberQueryService = mock(MemberQueryService.class);
    private final MemberSanctionRepository sanctionRepository = mock(MemberSanctionRepository.class);
    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final SanctionNotificationService notificationService = mock(SanctionNotificationService.class);
    private final SpaceCommentBlockService service = new SpaceCommentBlockService(
            creatorRepository, memberQueryService, sanctionRepository, memberRepository, notificationService,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private final Creator creator = creator(CREATOR_ID, CREATOR_MEMBER_ID);

    @BeforeEach
    void setUp() {
        given(creatorRepository.findByMemberId(CREATOR_MEMBER_ID)).willReturn(Optional.of(creator));
        stubMember(CREATOR_MEMBER_ID, MemberRole.USER);
        stubMember(TARGET_ID, MemberRole.USER);
        given(sanctionRepository.save(any(MemberSanction.class))).willAnswer(invocation -> {
            MemberSanction saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "sanctionId", 900L);
            return saved;
        });
        given(memberQueryService.findMemberInfosByIds(any())).willReturn(
                Map.of(TARGET_ID, new MemberInfo(TARGET_ID, "차단된팬", null, null)));
    }

    @Test
    void 차단하면_그_스페이스_범위의_수동_제한을_days_동안_만들고_대상에게_알린다() {
        SpaceCommentBlockView view = service.block(CREATOR_MEMBER_ID, TARGET_ID, 7);

        ArgumentCaptor<MemberSanction> captor = ArgumentCaptor.forClass(MemberSanction.class);
        verify(sanctionRepository).save(captor.capture());
        MemberSanction saved = captor.getValue();
        assertThat(saved.getScope()).isEqualTo(SanctionScope.SPACE);
        assertThat(saved.getType()).isEqualTo(SanctionType.COMMENT_RESTRICTION);
        // 경고 횟수에 세지 않는 수동 제한이며, 거는 주체는 크리에이터다.
        assertThat(saved.getSource()).isEqualTo(SanctionSource.MANUAL);
        assertThat(saved.getSpaceCreatorId()).isEqualTo(CREATOR_ID);
        assertThat(saved.getMemberId()).isEqualTo(TARGET_ID);
        assertThat(saved.getCreatedBy()).isEqualTo(CREATOR_MEMBER_ID);
        assertThat(saved.getStartedAt()).isEqualTo(NOW);
        assertThat(saved.getEndsAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(view.sanctionId()).isEqualTo(900L);
        assertThat(view.memberName()).isEqualTo("차단된팬");
        assertThat(view.active()).isTrue();
        verify(notificationService).createSpaceCommentBlockStarted(
                TARGET_ID, 900L, creator.publicDisplayName(), saved.getEndsAt());
    }

    @Test
    void 같은_회원에게_이미_유효한_차단이_있으면_새로_만들지_않고_기존_차단을_돌려준다() {
        MemberSanction existing = MemberSanction.spaceBlock(
                TARGET_ID, CREATOR_ID, CREATOR_MEMBER_ID, NOW.minusSeconds(3600), Duration.ofDays(3));
        ReflectionTestUtils.setField(existing, "sanctionId", 800L);
        given(sanctionRepository.findActiveSpaceBlocks(CREATOR_ID, TARGET_ID, NOW)).willReturn(List.of(existing));

        SpaceCommentBlockView view = service.block(CREATOR_MEMBER_ID, TARGET_ID, 30);

        assertThat(view.sanctionId()).isEqualTo(800L);
        assertThat(view.endsAt()).isEqualTo(existing.getEndsAt());
        verify(sanctionRepository, never()).save(any(MemberSanction.class));
        verifyNoInteractions(notificationService);
    }

    @Test
    void 차단은_두_회원_행을_회원_ID_오름차순으로_잠근_뒤_유효한_차단을_읽고_READ_COMMITTED로_실행한다() throws Exception {
        service.block(CREATOR_MEMBER_ID, TARGET_ID, 1);

        InOrder order = inOrder(memberRepository, sanctionRepository);
        // 크리에이터(10)와 대상(20): 작은 ID부터 잠근다.
        order.verify(memberRepository).findByIdForUpdate(CREATOR_MEMBER_ID);
        order.verify(memberRepository).findByIdForUpdate(TARGET_ID);
        order.verify(sanctionRepository).findActiveSpaceBlocks(CREATOR_ID, TARGET_ID, NOW);
        order.verify(sanctionRepository).save(any(MemberSanction.class));
        Transactional transactional = SpaceCommentBlockService.class
                .getMethod("block", Long.class, Long.class, int.class).getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.isolation()).isEqualTo(Isolation.READ_COMMITTED);
    }

    /**
     * 크리에이터 A(10)가 B(30)를, B가 A를 동시에 차단해도 두 요청이 같은 순서(10 → 30)로 잠가야 서로 상대 행을 기다리는
     * 순환 대기가 생기지 않는다. 여기서는 큰 ID의 크리에이터가 작은 ID의 대상을 차단할 때도 작은 ID부터 잠그는지 본다.
     */
    @Test
    void 크리에이터가_대상보다_ID가_커도_작은_ID부터_잠근다() {
        Long largerCreatorMemberId = 30L;
        given(creatorRepository.findByMemberId(largerCreatorMemberId))
                .willReturn(Optional.of(creator(6L, largerCreatorMemberId)));
        stubMember(largerCreatorMemberId, MemberRole.USER);

        service.block(largerCreatorMemberId, TARGET_ID, 1);

        InOrder order = inOrder(memberRepository);
        order.verify(memberRepository).findByIdForUpdate(TARGET_ID);
        order.verify(memberRepository).findByIdForUpdate(largerCreatorMemberId);
    }

    @Test
    void 관리자는_차단할_수_없고_차단도_알림도_저장되지_않는다() {
        stubMember(TARGET_ID, MemberRole.ADMIN);

        assertError(() -> service.block(CREATOR_MEMBER_ID, TARGET_ID, 1), SanctionErrorCode.CANNOT_BLOCK_ADMIN);

        // 대상 회원 행을 잠근 뒤에 역할을 확인하므로 같은 시점의 역할 변경과 경합하지 않는다.
        verify(memberRepository).findByIdForUpdate(TARGET_ID);
        verify(sanctionRepository, never()).save(any(MemberSanction.class));
        verifyNoInteractions(notificationService);
    }

    @Test
    void Creator가_아니면_차단할_수_없다() {
        given(creatorRepository.findByMemberId(77L)).willReturn(Optional.empty());

        assertError(() -> service.block(77L, TARGET_ID, 1), CommonErrorCode.FORBIDDEN);
        verify(sanctionRepository, never()).save(any(MemberSanction.class));
    }

    @Test
    void 자기_자신은_차단할_수_없다() {
        assertError(() -> service.block(CREATOR_MEMBER_ID, CREATOR_MEMBER_ID, 1), SanctionErrorCode.CANNOT_BLOCK_SELF);
        verify(sanctionRepository, never()).save(any(MemberSanction.class));
    }

    @Test
    void 기간이_1일_미만이거나_365일_초과면_거부한다() {
        assertError(() -> service.block(CREATOR_MEMBER_ID, TARGET_ID, 0), CommonErrorCode.VALIDATION_FAILED);
        assertError(() -> service.block(CREATOR_MEMBER_ID, TARGET_ID, 366), CommonErrorCode.VALIDATION_FAILED);
        verify(sanctionRepository, never()).save(any(MemberSanction.class));
    }

    @Test
    void 대상_회원이_없으면_404다() {
        given(memberRepository.findByIdForUpdate(999L)).willReturn(Optional.empty());

        assertError(() -> service.block(CREATOR_MEMBER_ID, 999L, 1), CommonErrorCode.RESOURCE_NOT_FOUND);
        verify(sanctionRepository, never()).save(any(MemberSanction.class));
    }

    @Test
    void 해제하면_지금부터_무효이고_차단됐던_회원에게_알리며_다시_해제해도_바뀌지_않는다() {
        MemberSanction block = block(900L, TARGET_ID, CREATOR_ID, NOW.minusSeconds(3600), Duration.ofDays(7));
        given(sanctionRepository.findByIdForUpdate(900L)).willReturn(Optional.of(block));

        SpaceCommentBlockView first = service.release(CREATOR_MEMBER_ID, 900L);
        SpaceCommentBlockView second = service.release(CREATOR_MEMBER_ID, 900L);

        assertThat(first.active()).isFalse();
        assertThat(first.releasedAt()).isEqualTo(NOW);
        assertThat(second.releasedAt()).isEqualTo(NOW);
        assertThat(block.getReleasedBy()).isEqualTo(CREATOR_MEMBER_ID);
        verify(notificationService).createSpaceCommentBlockReleased(TARGET_ID, 900L, creator.publicDisplayName());
        verify(notificationService, org.mockito.Mockito.times(1))
                .createSpaceCommentBlockReleased(any(), any(), anyString());
    }

    @Test
    void 이미_끝난_차단을_해제하면_알리지_않는다() {
        MemberSanction expired = block(900L, TARGET_ID, CREATOR_ID, NOW.minus(Duration.ofDays(3)), Duration.ofDays(1));
        given(sanctionRepository.findByIdForUpdate(900L)).willReturn(Optional.of(expired));

        service.release(CREATOR_MEMBER_ID, 900L);

        verifyNoInteractions(notificationService);
    }

    @Test
    void 해제는_제재_행_다음_두_회원_행을_ID_오름차순으로_잠그고_READ_COMMITTED로_실행한다() throws Exception {
        MemberSanction block = block(900L, TARGET_ID, CREATOR_ID, NOW.minusSeconds(3600), Duration.ofDays(7));
        given(sanctionRepository.findByIdForUpdate(900L)).willReturn(Optional.of(block));

        service.release(CREATOR_MEMBER_ID, 900L);

        InOrder order = inOrder(sanctionRepository, memberRepository);
        order.verify(sanctionRepository).findByIdForUpdate(900L);
        order.verify(memberRepository).findByIdForUpdate(CREATOR_MEMBER_ID);
        order.verify(memberRepository).findByIdForUpdate(TARGET_ID);
        Transactional transactional = SpaceCommentBlockService.class
                .getMethod("release", Long.class, Long.class).getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.isolation()).isEqualTo(Isolation.READ_COMMITTED);
    }

    @Test
    void 다른_크리에이터의_차단이나_플랫폼_제재는_404이고_바뀌지_않는다() {
        MemberSanction otherSpace = block(901L, TARGET_ID, 99L, NOW.minusSeconds(3600), Duration.ofDays(7));
        MemberSanction platform = MemberSanction.manual(TARGET_ID, 1L, "사유", NOW.minusSeconds(3600), Duration.ofDays(7));
        ReflectionTestUtils.setField(platform, "sanctionId", 902L);
        given(sanctionRepository.findByIdForUpdate(901L)).willReturn(Optional.of(otherSpace));
        given(sanctionRepository.findByIdForUpdate(902L)).willReturn(Optional.of(platform));

        assertError(() -> service.release(CREATOR_MEMBER_ID, 901L), CommonErrorCode.RESOURCE_NOT_FOUND);
        assertError(() -> service.release(CREATOR_MEMBER_ID, 902L), CommonErrorCode.RESOURCE_NOT_FOUND);
        assertThat(otherSpace.getReleasedAt()).isNull();
        assertThat(platform.getReleasedAt()).isNull();
        verifyNoInteractions(notificationService);
    }

    @Test
    void Creator가_아니면_해제할_수_없다() {
        given(creatorRepository.findByMemberId(77L)).willReturn(Optional.empty());

        assertError(() -> service.release(77L, 900L), CommonErrorCode.FORBIDDEN);
        verify(sanctionRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void 목록은_내_스페이스_차단만_차단된_회원_이름과_함께_돌려준다() {
        MemberSanction active = block(900L, TARGET_ID, CREATOR_ID, NOW.minusSeconds(60), Duration.ofDays(1));
        MemberSanction expired = block(899L, TARGET_ID, CREATOR_ID, NOW.minus(Duration.ofDays(3)), Duration.ofDays(1));
        given(sanctionRepository.searchSpaceBlocks(CREATOR_ID, false, NOW, PAGE))
                .willReturn(new PageImpl<>(List.of(active, expired), PAGE, 2));

        List<SpaceCommentBlockView> views = service.findBlocks(CREATOR_MEMBER_ID, false, PAGE).getContent();

        assertThat(views).extracting(SpaceCommentBlockView::active).containsExactly(true, false);
        assertThat(views).extracting(SpaceCommentBlockView::memberName).containsOnly("차단된팬");
        verify(memberQueryService).findMemberInfosByIds(Set.of(TARGET_ID));
    }

    @Test
    void Creator가_아니면_목록을_읽지_않는다() {
        given(creatorRepository.findByMemberId(77L)).willReturn(Optional.empty());

        assertError(() -> service.findBlocks(77L, false, PAGE), CommonErrorCode.FORBIDDEN);
        verify(sanctionRepository, never()).searchSpaceBlocks(any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any());
    }

    @Test
    void 관리자는_크리에이터를_지정해_차단하고_차단한_사람은_관리자로_기록되며_대상에게_알린다() {
        stubMember(ADMIN_ID, MemberRole.ADMIN);
        given(creatorRepository.findById(CREATOR_ID)).willReturn(Optional.of(creator));

        SpaceCommentBlockView view = service.blockByAdmin(ADMIN_ID, CREATOR_ID, TARGET_ID, 30);

        ArgumentCaptor<MemberSanction> captor = ArgumentCaptor.forClass(MemberSanction.class);
        verify(sanctionRepository).save(captor.capture());
        MemberSanction saved = captor.getValue();
        assertThat(saved.getScope()).isEqualTo(SanctionScope.SPACE);
        assertThat(saved.getSpaceCreatorId()).isEqualTo(CREATOR_ID);
        assertThat(saved.getMemberId()).isEqualTo(TARGET_ID);
        assertThat(saved.getCreatedBy()).isEqualTo(ADMIN_ID);
        assertThat(saved.getSource()).isEqualTo(SanctionSource.MANUAL);
        assertThat(saved.getEndsAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(view.createdBy()).isEqualTo(ADMIN_ID);
        assertThat(view.spaceCreatorId()).isEqualTo(CREATOR_ID);
        verify(memberQueryService).validateAdmin(ADMIN_ID);
        verify(notificationService).createSpaceCommentBlockStarted(
                TARGET_ID, 900L, creator.publicDisplayName(), saved.getEndsAt());
    }

    @Test
    void 관리자의_차단은_관리자_행을_잠그지_않고_대상_행만_잠가_플랫폼_제재와_순서를_맞춘다() throws Exception {
        stubMember(ADMIN_ID, MemberRole.ADMIN);
        given(creatorRepository.findById(CREATOR_ID)).willReturn(Optional.of(creator));

        service.blockByAdmin(ADMIN_ID, CREATOR_ID, TARGET_ID, 1);

        // 관리자 ID(1)가 대상(20)보다 작아도 관리자 행을 먼저 쓰기 잠금으로 잡지 않는다. 같은 관리자가 같은 대상에게 보낸
        // 플랫폼 제한은 대상 행을 잠근 뒤 created_by FK로 관리자 행을 기다리므로, 관리자 행을 먼저 잡으면 교착이 난다.
        verify(memberRepository).findByIdForUpdate(TARGET_ID);
        verify(memberRepository, never()).findByIdForUpdate(ADMIN_ID);
        InOrder order = inOrder(memberRepository, sanctionRepository);
        order.verify(memberRepository).findByIdForUpdate(TARGET_ID);
        order.verify(sanctionRepository).findActiveSpaceBlocks(CREATOR_ID, TARGET_ID, NOW);
        Transactional transactional = SpaceCommentBlockService.class
                .getMethod("blockByAdmin", Long.class, Long.class, Long.class, int.class).getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.isolation()).isEqualTo(Isolation.READ_COMMITTED);
    }

    @Test
    void 관리자_차단은_스페이스_주인_다른_관리자_자기_자신_대상을_거부한다() {
        stubMember(ADMIN_ID, MemberRole.ADMIN);
        stubMember(2L, MemberRole.ADMIN);
        given(creatorRepository.findById(CREATOR_ID)).willReturn(Optional.of(creator));

        assertError(() -> service.blockByAdmin(ADMIN_ID, CREATOR_ID, CREATOR_MEMBER_ID, 1),
                SanctionErrorCode.CANNOT_BLOCK_SPACE_OWNER);
        assertError(() -> service.blockByAdmin(ADMIN_ID, CREATOR_ID, 2L, 1), SanctionErrorCode.CANNOT_BLOCK_ADMIN);
        assertError(() -> service.blockByAdmin(ADMIN_ID, CREATOR_ID, ADMIN_ID, 1), SanctionErrorCode.CANNOT_BLOCK_SELF);
        verify(sanctionRepository, never()).save(any(MemberSanction.class));
        verifyNoInteractions(notificationService);
    }

    @Test
    void 관리자가_아니거나_없는_크리에이터면_차단하지_않는다() {
        org.mockito.BDDMockito.willThrow(new BusinessException(CommonErrorCode.FORBIDDEN))
                .given(memberQueryService).validateAdmin(7L);
        given(creatorRepository.findById(999L)).willReturn(Optional.empty());

        assertError(() -> service.blockByAdmin(7L, CREATOR_ID, TARGET_ID, 1), CommonErrorCode.FORBIDDEN);
        assertError(() -> service.blockByAdmin(ADMIN_ID, 999L, TARGET_ID, 1), CommonErrorCode.RESOURCE_NOT_FOUND);
        verify(sanctionRepository, never()).save(any(MemberSanction.class));
    }

    @Test
    void 관리자가_차단할_때_크리에이터가_건_유효한_차단이_있으면_새로_만들지_않는다() {
        stubMember(ADMIN_ID, MemberRole.ADMIN);
        given(creatorRepository.findById(CREATOR_ID)).willReturn(Optional.of(creator));
        MemberSanction byCreator = block(800L, TARGET_ID, CREATOR_ID, NOW.minusSeconds(3600), Duration.ofDays(3));
        given(sanctionRepository.findActiveSpaceBlocks(CREATOR_ID, TARGET_ID, NOW)).willReturn(List.of(byCreator));

        SpaceCommentBlockView view = service.blockByAdmin(ADMIN_ID, CREATOR_ID, TARGET_ID, 30);

        assertThat(view.sanctionId()).isEqualTo(800L);
        verify(sanctionRepository, never()).save(any(MemberSanction.class));
        verifyNoInteractions(notificationService);
    }

    @Test
    void 관리자는_크리에이터가_건_차단도_해제하고_해제한_사람은_관리자로_남으며_대상에게_알린다() {
        stubMember(ADMIN_ID, MemberRole.ADMIN);
        MemberSanction byCreator = block(900L, TARGET_ID, CREATOR_ID, NOW.minusSeconds(3600), Duration.ofDays(7));
        given(sanctionRepository.findByIdForUpdate(900L)).willReturn(Optional.of(byCreator));
        given(creatorRepository.findById(CREATOR_ID)).willReturn(Optional.of(creator));

        SpaceCommentBlockView view = service.releaseByAdmin(ADMIN_ID, 900L);

        assertThat(view.active()).isFalse();
        assertThat(byCreator.getReleasedBy()).isEqualTo(ADMIN_ID);
        verify(memberQueryService).validateAdmin(ADMIN_ID);
        verify(notificationService).createSpaceCommentBlockReleased(TARGET_ID, 900L, creator.publicDisplayName());
        // 관리자 해제도 제재 행 → 대상 회원 행 순서이고 관리자 행은 잠그지 않는다(플랫폼 제재 해제와 같은 순서).
        InOrder order = inOrder(sanctionRepository, memberRepository);
        order.verify(sanctionRepository).findByIdForUpdate(900L);
        order.verify(memberRepository).findByIdForUpdate(TARGET_ID);
        verify(memberRepository, never()).findByIdForUpdate(ADMIN_ID);
    }

    @Test
    void 관리자_해제는_플랫폼_제재나_없는_제재를_404로_거부하고_바꾸지_않는다() {
        MemberSanction platform = MemberSanction.manual(TARGET_ID, ADMIN_ID, "사유", NOW.minusSeconds(3600), Duration.ofDays(7));
        ReflectionTestUtils.setField(platform, "sanctionId", 902L);
        given(sanctionRepository.findByIdForUpdate(902L)).willReturn(Optional.of(platform));
        given(sanctionRepository.findByIdForUpdate(999L)).willReturn(Optional.empty());

        assertError(() -> service.releaseByAdmin(ADMIN_ID, 902L), CommonErrorCode.RESOURCE_NOT_FOUND);
        assertError(() -> service.releaseByAdmin(ADMIN_ID, 999L), CommonErrorCode.RESOURCE_NOT_FOUND);
        assertThat(platform.getReleasedAt()).isNull();
        verifyNoInteractions(notificationService);
    }

    @Test
    void 관리자가_아니면_해제와_목록을_읽지_않는다() {
        org.mockito.BDDMockito.willThrow(new BusinessException(CommonErrorCode.FORBIDDEN))
                .given(memberQueryService).validateAdmin(7L);

        assertError(() -> service.releaseByAdmin(7L, 900L), CommonErrorCode.FORBIDDEN);
        assertError(() -> service.findBlocksByAdmin(7L, null, null, false, PAGE), CommonErrorCode.FORBIDDEN);
        verify(sanctionRepository, never()).findByIdForUpdate(any());
        verify(sanctionRepository, never()).searchSpaceBlocksForAdmin(
                any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any());
    }

    @Test
    void 관리자_목록은_필터를_그대로_넘기고_차단한_사람과_스페이스_주인을_함께_돌려준다() {
        MemberSanction active = block(900L, TARGET_ID, CREATOR_ID, NOW.minusSeconds(60), Duration.ofDays(1));
        given(sanctionRepository.searchSpaceBlocksForAdmin(CREATOR_ID, TARGET_ID, true, NOW, PAGE))
                .willReturn(new PageImpl<>(List.of(active), PAGE, 1));

        List<SpaceCommentBlockView> views = service.findBlocksByAdmin(ADMIN_ID, CREATOR_ID, TARGET_ID, true, PAGE)
                .getContent();

        assertThat(views).singleElement().satisfies(found -> {
            assertThat(found.spaceCreatorId()).isEqualTo(CREATOR_ID);
            assertThat(found.createdBy()).isEqualTo(CREATOR_MEMBER_ID);
            assertThat(found.memberName()).isEqualTo("차단된팬");
        });
        verify(memberQueryService).validateAdmin(ADMIN_ID);
    }

    private MemberSanction block(Long sanctionId, Long memberId, Long spaceCreatorId, Instant startedAt, Duration duration) {
        MemberSanction block = MemberSanction.spaceBlock(memberId, spaceCreatorId, CREATOR_MEMBER_ID, startedAt, duration);
        ReflectionTestUtils.setField(block, "sanctionId", sanctionId);
        return block;
    }

    /** 회원 mock을 먼저 만든 뒤 스터빙한다(given 안에서 다른 mock을 스터빙하면 Mockito가 거부한다). */
    private void stubMember(Long memberId, MemberRole role) {
        Member member = mock(Member.class);
        given(member.getRole()).willReturn(role);
        given(memberRepository.findByIdForUpdate(memberId)).willReturn(Optional.of(member));
    }

    private Creator creator(Long creatorId, Long memberId) {
        Creator created = new Creator(memberId, "크리에이터");
        ReflectionTestUtils.setField(created, "creatorId", creatorId);
        return created;
    }

    private void assertError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }
}
