package kr.co.cking.mission.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.follow.application.CreatorFollowQueryService;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.domain.WelcomeQuestStatus;
import kr.co.cking.mission.domain.WelcomeQuestStep;
import kr.co.cking.ticket.application.CommonTicketOnceEarnService;
import kr.co.cking.ticket.application.dto.EarnLookupResult;
import kr.co.cking.ticket.application.dto.EarnLookupStatus;
import kr.co.cking.ticket.application.dto.EarnResult;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 웰컴 대상·상태·지급 조건을 Ticket 경계에서 검증한다. */
class WelcomeQuestServiceTest {
    private final MemberRepository members = mock(MemberRepository.class);
    private final CreatorFollowQueryService follows = mock(CreatorFollowQueryService.class);
    private final CommonTicketOnceEarnService tickets = mock(CommonTicketOnceEarnService.class);
    private final WelcomeQuestService service = new WelcomeQuestService(members, follows, tickets);

    /** 활성화 직전 회원은 상태 조회에서 비대상이며 지급도 받지 못한다. */
    @Test
    void 활성화_전_회원은_비대상이다() {
        memberAt(LocalDateTime.parse("2026-10-14T23:59:59"));
        when(tickets.findExisting(any())).thenReturn(new EarnLookupResult(EarnLookupStatus.NOT_FOUND));
        assertThat(service.findState(1L).eligible()).isFalse();
        assertThatThrownBy(() -> service.claim(1L, WelcomeQuestStep.SIGNUP, UUID.randomUUID(), null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("웰컴 퀘스트 대상");
        verify(tickets, never()).earn(any());
    }

    /** FOLLOW 미지급 상태만 현재 팔로우 수에 따라 변하고 보너스는 독립적이다. */
    @Test
    void 상태_조회는_승인_이력과_현재_팔로우를_읽는다() {
        memberAt(LocalDateTime.parse("2026-10-15T00:00:00"));
        when(tickets.findAcceptedRewardTypes(1L)).thenReturn(Set.of("SIGNUP", "QUIZ"));
        when(follows.countFollowedCreators(1L)).thenReturn(0L, 1L);
        var first = service.findState(1L);
        var second = service.findState(1L);
        assertThat(first.items().get(1).status()).isEqualTo(WelcomeQuestStatus.NOT_MET);
        assertThat(second.items().get(1).status()).isEqualTo(WelcomeQuestStatus.CLAIMABLE);
        assertThat(first.bonus().status()).isEqualTo(WelcomeQuestStatus.CLAIMABLE);
        assertThat(first.completed()).isFalse();
        verify(tickets, never()).earn(any());
    }

    /** 오답은 적립 없이 거절하고 정답은 별도 시도에서 승인한다. */
    @Test
    void 퀴즈_오답은_지급하지_않고_정답을_승인한다() {
        memberAt(LocalDateTime.parse("2026-10-15T00:00:00"));
        when(tickets.findExisting(any())).thenReturn(new EarnLookupResult(EarnLookupStatus.NOT_FOUND));
        when(tickets.findAcceptedRewardTypes(1L)).thenReturn(Set.of());
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> service.claim(1L, WelcomeQuestStep.QUIZ, UUID.randomUUID(), false))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("정답");
        }
        verify(tickets, never()).earn(any());
        when(tickets.earn(any())).thenReturn(new EarnResult(EarnResultCode.EARN_ACCEPTED));
        assertThat(service.claim(1L, WelcomeQuestStep.QUIZ, UUID.randomUUID(), true).code())
                .isEqualTo(EarnResultCode.EARN_ACCEPTED);
    }

    /** FOLLOW는 조회 때의 값이 아닌 지급 요청 순간의 팔로우 수를 다시 확인한다. */
    @Test
    void FOLLOW는_지급_시점의_팔로우_관계를_재검증한다() {
        memberAt(LocalDateTime.parse("2026-10-15T00:00:00"));
        when(tickets.findExisting(any())).thenReturn(new EarnLookupResult(EarnLookupStatus.NOT_FOUND));
        when(tickets.findAcceptedRewardTypes(1L)).thenReturn(Set.of());
        when(follows.countFollowedCreators(1L)).thenReturn(0L, 1L);
        assertThatThrownBy(() -> service.claim(1L, WelcomeQuestStep.FOLLOW, UUID.randomUUID(), null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("팔로우");
        verify(tickets, never()).earn(any());
        when(tickets.earn(any())).thenReturn(new EarnResult(EarnResultCode.EARN_ACCEPTED));
        assertThat(service.claim(1L, WelcomeQuestStep.FOLLOW, UUID.randomUUID(), null).code())
                .isEqualTo(EarnResultCode.EARN_ACCEPTED);
    }

    /** 세 단계가 모두 승인되면 독립 보너스가 미수령이어도 완료다. */
    @Test
    void 세_단계_승인만으로_completed를_계산한다() {
        memberAt(LocalDateTime.parse("2026-10-15T00:00:00"));
        when(tickets.findAcceptedRewardTypes(1L)).thenReturn(Set.of("SIGNUP", "FOLLOW", "QUIZ"));
        assertThat(service.findState(1L).completed()).isTrue();
        assertThat(service.findState(1L).bonus().status()).isEqualTo(WelcomeQuestStatus.CLAIMABLE);
    }

    /** 이미 승인된 동일 UUID는 팔로우가 없어져도 같은 성공을 재현한다. */
    @Test
    void 동일_requestId_재시도는_현재_조건보다_먼저_승인을_재현한다() {
        memberAt(LocalDateTime.parse("2026-10-15T00:00:00"));
        when(tickets.findExisting(any())).thenReturn(new EarnLookupResult(EarnLookupStatus.ALREADY_PROCESSED));
        assertThat(service.claim(1L, WelcomeQuestStep.FOLLOW, UUID.randomUUID(), null).code())
                .isEqualTo(EarnResultCode.ALREADY_PROCESSED);
        verify(follows, never()).countFollowedCreators(1L);
        verify(tickets, never()).earn(any());
    }

    /** 저장된 Member의 UTC 생성 시각을 지정한다. */
    private void memberAt(LocalDateTime createdAt) {
        Member member = mock(Member.class);
        when(member.getCreatedAt()).thenReturn(createdAt);
        when(members.findById(1L)).thenReturn(Optional.of(member));
    }
}
