package kr.co.cking.sanction.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.sanction.application.dto.SanctionView;
import kr.co.cking.sanction.domain.MemberSanction;
import kr.co.cking.sanction.domain.SanctionErrorCode;
import kr.co.cking.sanction.domain.SanctionScope;
import kr.co.cking.sanction.domain.SanctionType;
import kr.co.cking.sanction.repository.MemberSanctionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SanctionQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");
    private static final Long MEMBER_ID = 20L;
    private static final Long CREATOR_ID = 5L;
    private static final Pageable PAGE = PageRequest.of(0, 20);

    private final MemberQueryService memberQueryService = mock(MemberQueryService.class);
    private final MemberSanctionRepository sanctionRepository = mock(MemberSanctionRepository.class);
    private final SanctionQueryService service = new SanctionQueryService(
            memberQueryService, sanctionRepository, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void 유효한_댓글_제한이_있으면_해제_시각을_담아_COMMENT_RESTRICTED로_막는다() {
        Instant endsAt = NOW.plus(Duration.ofDays(3));
        given(sanctionRepository.findActiveEndsAt(MEMBER_ID, SanctionScope.PLATFORM, SanctionType.COMMENT_RESTRICTION, NOW))
                .willReturn(Optional.of(endsAt));

        assertThatThrownBy(() -> service.requireCommentWriteAllowed(MEMBER_ID, CREATOR_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(SanctionErrorCode.COMMENT_RESTRICTED);
                    assertThat(exception.getMessage()).contains(endsAt.toString());
                });
    }

    @Test
    void 유효한_제한이_없으면_통과한다() {
        given(sanctionRepository.findActiveEndsAt(MEMBER_ID, SanctionScope.PLATFORM, SanctionType.COMMENT_RESTRICTION, NOW))
                .willReturn(Optional.empty());

        service.requireCommentWriteAllowed(MEMBER_ID, CREATOR_ID);
    }

    @Test
    void 그_스페이스의_차단이_유효하면_해제_시각을_담아_SPACE_COMMENT_BLOCKED로_막는다() {
        Instant endsAt = NOW.plus(Duration.ofDays(7));
        given(sanctionRepository.findActiveEndsAt(MEMBER_ID, SanctionScope.PLATFORM, SanctionType.COMMENT_RESTRICTION, NOW))
                .willReturn(Optional.empty());
        given(sanctionRepository.findActiveSpaceBlockEndsAt(CREATOR_ID, MEMBER_ID, NOW)).willReturn(Optional.of(endsAt));

        assertThatThrownBy(() -> service.requireCommentWriteAllowed(MEMBER_ID, CREATOR_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(SanctionErrorCode.SPACE_COMMENT_BLOCKED);
                    assertThat(exception.getMessage()).contains(endsAt.toString());
                });
    }

    @Test
    void 다른_스페이스의_차단은_이_스페이스의_작성을_막지_않는다() {
        given(sanctionRepository.findActiveEndsAt(MEMBER_ID, SanctionScope.PLATFORM, SanctionType.COMMENT_RESTRICTION, NOW))
                .willReturn(Optional.empty());
        given(sanctionRepository.findActiveSpaceBlockEndsAt(CREATOR_ID, MEMBER_ID, NOW)).willReturn(Optional.empty());

        service.requireCommentWriteAllowed(MEMBER_ID, CREATOR_ID);
    }

    @Test
    void 플랫폼_제한과_스페이스_차단이_함께_있으면_플랫폼_제한을_먼저_알린다() {
        given(sanctionRepository.findActiveEndsAt(MEMBER_ID, SanctionScope.PLATFORM, SanctionType.COMMENT_RESTRICTION, NOW))
                .willReturn(Optional.of(NOW.plus(Duration.ofDays(1))));
        given(sanctionRepository.findActiveSpaceBlockEndsAt(CREATOR_ID, MEMBER_ID, NOW))
                .willReturn(Optional.of(NOW.plus(Duration.ofDays(7))));

        assertThatThrownBy(() -> service.requireCommentWriteAllowed(MEMBER_ID, CREATOR_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(SanctionErrorCode.COMMENT_RESTRICTED));
    }

    @Test
    void 관리자_목록은_관리자만_보고_지금_유효한지를_함께_돌려준다() {
        MemberSanction active = MemberSanction.manual(MEMBER_ID, 1L, "사유", NOW.minusSeconds(60), Duration.ofDays(1));
        MemberSanction expired = MemberSanction.manual(MEMBER_ID, 1L, "사유", NOW.minus(Duration.ofDays(3)), Duration.ofDays(1));
        given(sanctionRepository.search(MEMBER_ID, false, NOW, PAGE))
                .willReturn(new PageImpl<>(List.of(active, expired), PAGE, 2));

        List<SanctionView> views = service.findSanctions(1L, MEMBER_ID, false, PAGE).getContent();

        assertThat(views).extracting(SanctionView::active).containsExactly(true, false);
        verify(memberQueryService).validateAdmin(1L);
    }

    @Test
    void 관리자가_아니면_목록을_읽지_않는다() {
        willThrow(new BusinessException(CommonErrorCode.FORBIDDEN)).given(memberQueryService).validateAdmin(7L);

        assertThatThrownBy(() -> service.findSanctions(7L, null, false, PAGE))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.FORBIDDEN));
        verify(sanctionRepository, never()).search(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
