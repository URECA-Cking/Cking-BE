package kr.co.cking.ticket.application;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import kr.co.cking.follow.application.CreatorFollowQueryService;
import kr.co.cking.ticket.application.dto.CommonTicketBalanceResponse;
import kr.co.cking.ticket.application.dto.MyTicketsSummaryResponse;
import kr.co.cking.ticket.application.dto.MyTicketsSummaryResponse.CreatorTicket;
import kr.co.cking.ticket.domain.UserTicketBalance;
import kr.co.cking.ticket.repository.UserTicketBalanceRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class MyTicketsSummaryServiceTest {

    private static final long MEMBER_ID = 7L;
    private static final Instant COMMON_UPDATED = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant CREATOR_UPDATED = Instant.parse("2026-10-02T00:00:00Z");

    private final CreatorFollowQueryService followQueryService = mock(CreatorFollowQueryService.class);
    private final CommonTicketBalanceQueryService commonBalanceQueryService = mock(CommonTicketBalanceQueryService.class);
    private final UserTicketBalanceRepository balanceRepository = mock(UserTicketBalanceRepository.class);
    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final CreatorSpaceRepository creatorSpaceRepository = mock(CreatorSpaceRepository.class);
    private final MyTicketsSummaryService service = new MyTicketsSummaryService(
            followQueryService, commonBalanceQueryService, balanceRepository, creatorRepository, creatorSpaceRepository);

    @Test
    void 팔로우한_크리에이터가_없으면_공용_잔액만_돌려주고_크리에이터를_조회하지_않는다() {
        given(commonBalanceQueryService.getBalanceDetail(MEMBER_ID))
                .willReturn(new CommonTicketBalanceResponse(MEMBER_ID, 3L, COMMON_UPDATED));
        given(followQueryService.findFollowedCreatorIds(MEMBER_ID)).willReturn(List.of());

        MyTicketsSummaryResponse result = service.getSummary(MEMBER_ID);

        assertThat(result.common()).isEqualTo(new MyTicketsSummaryResponse.CommonBalance(3L, COMMON_UPDATED));
        assertThat(result.creators()).isEmpty();
        assertThat(result.creatorTotal()).isZero();
        assertThat(result.total()).isEqualTo(3L);
        verify(balanceRepository, never()).findByMemberIdAndCreatorIdIn(any(), any());
        verify(creatorRepository, never()).findByCreatorIdIn(any());
        verify(creatorSpaceRepository, never()).findByCreatorIdIn(any());
    }

    @Test
    void 공용_잔액_행이_없으면_0과_null_갱신시각이다() {
        given(commonBalanceQueryService.getBalanceDetail(MEMBER_ID))
                .willReturn(new CommonTicketBalanceResponse(MEMBER_ID, 0L, null));
        given(followQueryService.findFollowedCreatorIds(MEMBER_ID)).willReturn(List.of());

        MyTicketsSummaryResponse result = service.getSummary(MEMBER_ID);

        assertThat(result.common()).isEqualTo(new MyTicketsSummaryResponse.CommonBalance(0L, null));
        assertThat(result.total()).isZero();
    }

    @Test
    void 팔로우한_크리에이터_순서대로_이름_이미지_잔액을_채운다() {
        given(commonBalanceQueryService.getBalanceDetail(MEMBER_ID))
                .willReturn(new CommonTicketBalanceResponse(MEMBER_ID, 2L, COMMON_UPDATED));
        given(followQueryService.findFollowedCreatorIds(MEMBER_ID)).willReturn(List.of(1L, 2L));
        given(balanceRepository.findByMemberIdAndCreatorIdIn(MEMBER_ID, List.of(1L, 2L)))
                .willReturn(List.of(balance(2L, 5L), balance(1L, 4L)));
        List<Creator> creators1 = List.of(creator(2L, "둘째"), creator(1L, "첫째"));
        List<CreatorSpace> spaces1 = List.of(space(1L, "https://img/1"), space(2L, "https://img/2"));
        given(creatorRepository.findByCreatorIdIn(List.of(1L, 2L))).willReturn(creators1);
        given(creatorSpaceRepository.findByCreatorIdIn(List.of(1L, 2L))).willReturn(spaces1);

        MyTicketsSummaryResponse result = service.getSummary(MEMBER_ID);

        assertThat(result.creators()).containsExactly(
                new CreatorTicket(1L, "첫째", "https://img/1", 4L, CREATOR_UPDATED),
                new CreatorTicket(2L, "둘째", "https://img/2", 5L, CREATOR_UPDATED));
    }

    @Test
    void 잔액_행이_없는_크리에이터는_0과_null_갱신시각이다() {
        given(commonBalanceQueryService.getBalanceDetail(MEMBER_ID))
                .willReturn(new CommonTicketBalanceResponse(MEMBER_ID, 0L, null));
        given(followQueryService.findFollowedCreatorIds(MEMBER_ID)).willReturn(List.of(1L, 2L));
        given(balanceRepository.findByMemberIdAndCreatorIdIn(MEMBER_ID, List.of(1L, 2L)))
                .willReturn(List.of(balance(1L, 4L)));
        List<Creator> creators2 = List.of(creator(1L, "첫째"), creator(2L, "둘째"));
        List<CreatorSpace> spaces2 = List.of(space(1L, "https://img/1"), space(2L, "https://img/2"));
        given(creatorRepository.findByCreatorIdIn(List.of(1L, 2L))).willReturn(creators2);
        given(creatorSpaceRepository.findByCreatorIdIn(List.of(1L, 2L))).willReturn(spaces2);

        MyTicketsSummaryResponse result = service.getSummary(MEMBER_ID);

        assertThat(result.creators())
                .extracting(CreatorTicket::creatorId, CreatorTicket::balance, CreatorTicket::updatedAt)
                .containsExactly(tuple(1L, 4L, CREATOR_UPDATED), tuple(2L, 0L, null));
    }

    @Test
    void Space가_없는_크리에이터는_프로필_이미지가_null이다() {
        given(commonBalanceQueryService.getBalanceDetail(MEMBER_ID))
                .willReturn(new CommonTicketBalanceResponse(MEMBER_ID, 0L, null));
        given(followQueryService.findFollowedCreatorIds(MEMBER_ID)).willReturn(List.of(1L));
        given(balanceRepository.findByMemberIdAndCreatorIdIn(MEMBER_ID, List.of(1L)))
                .willReturn(List.of(balance(1L, 4L)));
        List<Creator> creators3 = List.of(creator(1L, "첫째"));
        given(creatorRepository.findByCreatorIdIn(List.of(1L))).willReturn(creators3);
        given(creatorSpaceRepository.findByCreatorIdIn(List.of(1L))).willReturn(List.of());

        MyTicketsSummaryResponse result = service.getSummary(MEMBER_ID);

        assertThat(result.creators()).singleElement().satisfies(creator -> {
            assertThat(creator.creatorName()).isEqualTo("첫째");
            assertThat(creator.profileImageUrl()).isNull();
        });
    }

    @Test
    void 합계는_목록과_일치하고_공용_잔액을_더한_값이다() {
        given(commonBalanceQueryService.getBalanceDetail(MEMBER_ID))
                .willReturn(new CommonTicketBalanceResponse(MEMBER_ID, 6L, COMMON_UPDATED));
        given(followQueryService.findFollowedCreatorIds(MEMBER_ID)).willReturn(List.of(1L, 2L, 3L));
        given(balanceRepository.findByMemberIdAndCreatorIdIn(MEMBER_ID, List.of(1L, 2L, 3L)))
                .willReturn(List.of(balance(1L, 4L), balance(2L, 3L)));
        List<Creator> creators5 = List.of(creator(1L, "a"), creator(2L, "b"), creator(3L, "c"));
        given(creatorRepository.findByCreatorIdIn(List.of(1L, 2L, 3L))).willReturn(creators5);
        given(creatorSpaceRepository.findByCreatorIdIn(List.of(1L, 2L, 3L))).willReturn(List.of());

        MyTicketsSummaryResponse result = service.getSummary(MEMBER_ID);

        long listSum = result.creators().stream().mapToLong(CreatorTicket::balance).sum();
        assertThat(listSum).isEqualTo(7L);
        assertThat(result.creatorTotal()).isEqualTo(listSum);
        assertThat(result.total()).isEqualTo(6L + 7L);
    }

    @Test
    void 팔로우하지_않은_크리에이터의_잔액은_포함하지_않는다() {
        given(commonBalanceQueryService.getBalanceDetail(MEMBER_ID))
                .willReturn(new CommonTicketBalanceResponse(MEMBER_ID, 0L, null));
        given(followQueryService.findFollowedCreatorIds(MEMBER_ID)).willReturn(List.of(1L));
        // 조회 결과에 팔로우하지 않은 크리에이터(99) 행이 섞여 들어와도 응답 목록·합계에서 제외한다.
        given(balanceRepository.findByMemberIdAndCreatorIdIn(MEMBER_ID, List.of(1L)))
                .willReturn(List.of(balance(1L, 4L), balance(99L, 50L)));
        List<Creator> creators4 = List.of(creator(1L, "첫째"));
        given(creatorRepository.findByCreatorIdIn(List.of(1L))).willReturn(creators4);
        given(creatorSpaceRepository.findByCreatorIdIn(List.of(1L))).willReturn(List.of());

        MyTicketsSummaryResponse result = service.getSummary(MEMBER_ID);

        assertThat(result.creators()).extracting(CreatorTicket::creatorId).containsExactly(1L);
        assertThat(result.creatorTotal()).isEqualTo(4L);
    }

    @Test
    void 크리에이터가_여럿이어도_잔액과_프로필은_각각_한_번만_조회한다() {
        List<Long> ids = List.of(1L, 2L, 3L, 4L);
        given(commonBalanceQueryService.getBalanceDetail(MEMBER_ID))
                .willReturn(new CommonTicketBalanceResponse(MEMBER_ID, 0L, null));
        given(followQueryService.findFollowedCreatorIds(MEMBER_ID)).willReturn(ids);
        given(balanceRepository.findByMemberIdAndCreatorIdIn(MEMBER_ID, ids)).willReturn(List.of());
        given(creatorRepository.findByCreatorIdIn(ids)).willReturn(List.of());
        given(creatorSpaceRepository.findByCreatorIdIn(ids)).willReturn(List.of());

        service.getSummary(MEMBER_ID);

        verify(balanceRepository, times(1)).findByMemberIdAndCreatorIdIn(MEMBER_ID, ids);
        verify(creatorRepository, times(1)).findByCreatorIdIn(ids);
        verify(creatorSpaceRepository, times(1)).findByCreatorIdIn(ids);
        verify(balanceRepository, never()).findByMemberIdAndCreatorId(any(), any());
    }

    private static UserTicketBalance balance(long creatorId, long balance) {
        return UserTicketBalance.builder()
                .memberId(MEMBER_ID)
                .creatorId(creatorId)
                .balance(balance)
                .updatedAt(CREATOR_UPDATED)
                .build();
    }

    private static Creator creator(long creatorId, String name) {
        Creator creator = mock(Creator.class);
        given(creator.getCreatorId()).willReturn(creatorId);
        given(creator.publicDisplayName()).willReturn(name);
        return creator;
    }

    private static CreatorSpace space(long creatorId, String profileImageUrl) {
        CreatorSpace space = mock(CreatorSpace.class);
        given(space.getCreatorId()).willReturn(creatorId);
        given(space.getProfileImageUrl()).willReturn(profileImageUrl);
        return space;
    }
}
