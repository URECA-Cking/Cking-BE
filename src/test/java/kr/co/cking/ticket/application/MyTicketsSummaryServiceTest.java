package kr.co.cking.ticket.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;

import kr.co.cking.creator.application.CreatorPublicProfileQueryService;
import kr.co.cking.creator.application.dto.CreatorPublicProfile;
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
    private final CreatorPublicProfileQueryService profileQueryService = mock(CreatorPublicProfileQueryService.class);
    private final MyTicketsSummaryService service = new MyTicketsSummaryService(
            followQueryService, commonBalanceQueryService, balanceRepository, profileQueryService);

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
        verify(profileQueryService, never()).findByCreatorIds(any());
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
        given(profileQueryService.findByCreatorIds(List.of(1L, 2L))).willReturn(Map.of(
                2L, new CreatorPublicProfile(2L, "둘째", "https://img/2"),
                1L, new CreatorPublicProfile(1L, "첫째", "https://img/1")));

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
        given(profileQueryService.findByCreatorIds(List.of(1L, 2L))).willReturn(Map.of(
                1L, new CreatorPublicProfile(1L, "첫째", "https://img/1"),
                2L, new CreatorPublicProfile(2L, "둘째", "https://img/2")));

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
        given(profileQueryService.findByCreatorIds(List.of(1L)))
                .willReturn(Map.of(1L, new CreatorPublicProfile(1L, "첫째", null)));

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
        given(profileQueryService.findByCreatorIds(List.of(1L, 2L, 3L))).willReturn(Map.of(
                1L, new CreatorPublicProfile(1L, "a", null),
                2L, new CreatorPublicProfile(2L, "b", null),
                3L, new CreatorPublicProfile(3L, "c", null)));

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
        given(profileQueryService.findByCreatorIds(List.of(1L)))
                .willReturn(Map.of(1L, new CreatorPublicProfile(1L, "첫째", null)));

        MyTicketsSummaryResponse result = service.getSummary(MEMBER_ID);

        assertThat(result.creators()).extracting(CreatorTicket::creatorId).containsExactly(1L);
        assertThat(result.creatorTotal()).isEqualTo(4L);
    }

    @Test
    void Creator_행이_없는_팔로우는_목록과_합계에서_함께_제외한다() {
        given(commonBalanceQueryService.getBalanceDetail(MEMBER_ID))
                .willReturn(new CommonTicketBalanceResponse(MEMBER_ID, 1L, COMMON_UPDATED));
        given(followQueryService.findFollowedCreatorIds(MEMBER_ID)).willReturn(List.of(1L, 2L));
        given(balanceRepository.findByMemberIdAndCreatorIdIn(MEMBER_ID, List.of(1L, 2L)))
                .willReturn(List.of(balance(1L, 4L), balance(2L, 9L)));
        // creatorId 2는 Creator 조회에서 빠진다(FK상 없을 수 없지만 어긋난 경우의 방어).
        given(profileQueryService.findByCreatorIds(List.of(1L, 2L)))
                .willReturn(Map.of(1L, new CreatorPublicProfile(1L, "첫째", null)));

        MyTicketsSummaryResponse result = service.getSummary(MEMBER_ID);

        assertThat(result.creators()).extracting(CreatorTicket::creatorId).containsExactly(1L);
        assertThat(result.creatorTotal()).isEqualTo(4L);
        assertThat(result.total()).isEqualTo(5L);
    }

    @Test
    void 크리에이터가_여럿이어도_잔액과_프로필은_각각_한_번만_조회한다() {
        List<Long> ids = List.of(1L, 2L, 3L, 4L);
        given(commonBalanceQueryService.getBalanceDetail(MEMBER_ID))
                .willReturn(new CommonTicketBalanceResponse(MEMBER_ID, 0L, null));
        given(followQueryService.findFollowedCreatorIds(MEMBER_ID)).willReturn(ids);
        given(balanceRepository.findByMemberIdAndCreatorIdIn(MEMBER_ID, ids)).willReturn(List.of());
        given(profileQueryService.findByCreatorIds(ids)).willReturn(Map.of());

        service.getSummary(MEMBER_ID);

        verify(balanceRepository, times(1)).findByMemberIdAndCreatorIdIn(MEMBER_ID, ids);
        verify(profileQueryService, times(1)).findByCreatorIds(ids);
        verify(balanceRepository, never()).findByMemberIdAndCreatorId(any(), any());
    }

    @Test
    void 팔로우가_경고_기준을_넘어도_상한_없이_전부_조회한다() {
        int count = MyTicketsSummaryService.LARGE_FOLLOW_COUNT_WARN_THRESHOLD + 1;
        List<Long> ids = LongStream.rangeClosed(1, count).boxed().toList();
        given(commonBalanceQueryService.getBalanceDetail(MEMBER_ID))
                .willReturn(new CommonTicketBalanceResponse(MEMBER_ID, 0L, null));
        given(followQueryService.findFollowedCreatorIds(MEMBER_ID)).willReturn(ids);
        given(balanceRepository.findByMemberIdAndCreatorIdIn(MEMBER_ID, ids)).willReturn(List.of());
        Map<Long, CreatorPublicProfile> profiles = ids.stream().collect(
                java.util.stream.Collectors.toMap(id -> id, id -> new CreatorPublicProfile(id, "c" + id, null)));
        given(profileQueryService.findByCreatorIds(ids)).willReturn(profiles);

        MyTicketsSummaryResponse result = service.getSummary(MEMBER_ID);

        // 경고는 로그만 남기고 결과를 자르지 않는다 — 합계가 전체 기준이어야 하기 때문이다.
        assertThat(result.creators()).hasSize(count);
    }

    private static UserTicketBalance balance(long creatorId, long balance) {
        return UserTicketBalance.builder()
                .memberId(MEMBER_ID)
                .creatorId(creatorId)
                .balance(balance)
                .updatedAt(CREATOR_UPDATED)
                .build();
    }
}
