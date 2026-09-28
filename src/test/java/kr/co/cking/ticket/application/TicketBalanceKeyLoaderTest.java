package kr.co.cking.ticket.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import kr.co.cking.stream.application.UnappliedBalanceMessageChecker;
import kr.co.cking.ticket.domain.CouponType;
import kr.co.cking.ticket.domain.UserCommonTicketBalance;
import kr.co.cking.ticket.domain.UserTicketBalance;
import kr.co.cking.ticket.repository.UserCommonTicketBalanceRepository;
import kr.co.cking.ticket.repository.UserTicketBalanceRepository;

class TicketBalanceKeyLoaderTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long CREATOR_ID = 2L;
    private static final String TOKEN = "token";

    private final TicketMaintenanceLock lock = mock(TicketMaintenanceLock.class);
    private final UnappliedBalanceMessageChecker checker = mock(UnappliedBalanceMessageChecker.class);
    private final UserTicketBalanceRepository creatorRepository = mock(UserTicketBalanceRepository.class);
    private final UserCommonTicketBalanceRepository commonRepository = mock(UserCommonTicketBalanceRepository.class);

    private TicketBalanceKeyLoader loader;

    @BeforeEach
    void setUp() {
        loader = new TicketBalanceKeyLoader(lock, checker, creatorRepository, commonRepository);
    }

    @Test
    void CREATOR는_DB_잔액으로_SET_NX_적재하고_락을_푼다() {
        when(lock.acquire(CREATOR_ID, MEMBER_ID)).thenReturn(TOKEN);
        when(checker.exists(MEMBER_ID, CREATOR_ID)).thenReturn(false);
        when(creatorRepository.findByMemberIdAndCreatorId(MEMBER_ID, CREATOR_ID)).thenReturn(Optional.of(
                UserTicketBalance.builder().memberId(MEMBER_ID).creatorId(CREATOR_ID).balance(7L)
                        .updatedAt(Instant.now()).build()));
        when(lock.loadBalanceIfHeld(CREATOR_ID, MEMBER_ID, TOKEN, 7L)).thenReturn(true);

        assertThat(loader.load(CouponType.CREATOR, CREATOR_ID, MEMBER_ID)).isTrue();

        verify(lock).loadBalanceIfHeld(CREATOR_ID, MEMBER_ID, TOKEN, 7L);
        verify(lock).release(CREATOR_ID, MEMBER_ID, TOKEN);
    }

    @Test
    void COMMON은_DB_잔액으로_적재하고_행이_없으면_0을_적재한다() {
        when(lock.acquireCommon(MEMBER_ID)).thenReturn(TOKEN);
        when(checker.existsCommon(MEMBER_ID)).thenReturn(false);
        when(commonRepository.findById(MEMBER_ID)).thenReturn(Optional.empty());
        when(lock.loadCommonBalanceIfHeld(MEMBER_ID, TOKEN, 0L)).thenReturn(true);

        assertThat(loader.load(CouponType.COMMON, CREATOR_ID, MEMBER_ID)).isTrue();

        verify(lock).loadCommonBalanceIfHeld(MEMBER_ID, TOKEN, 0L);
        verify(lock).releaseCommon(MEMBER_ID, TOKEN);
    }

    @Test
    void COMMON_DB_잔액이_있으면_그_값으로_적재한다() {
        when(lock.acquireCommon(MEMBER_ID)).thenReturn(TOKEN);
        when(commonRepository.findById(MEMBER_ID)).thenReturn(Optional.of(
                UserCommonTicketBalance.builder().memberId(MEMBER_ID).balance(4L).updatedAt(Instant.now()).build()));
        when(lock.loadCommonBalanceIfHeld(MEMBER_ID, TOKEN, 4L)).thenReturn(true);

        assertThat(loader.load(CouponType.COMMON, CREATOR_ID, MEMBER_ID)).isTrue();

        verify(lock).loadCommonBalanceIfHeld(MEMBER_ID, TOKEN, 4L);
    }

    @Test
    void 미반영_메시지가_있으면_적재하지_않고_락은_푼다() {
        when(lock.acquire(CREATOR_ID, MEMBER_ID)).thenReturn(TOKEN);
        when(checker.exists(MEMBER_ID, CREATOR_ID)).thenReturn(true);

        assertThat(loader.load(CouponType.CREATOR, CREATOR_ID, MEMBER_ID)).isFalse();

        verify(lock, never()).loadBalanceIfHeld(CREATOR_ID, MEMBER_ID, TOKEN, 0L);
        verify(lock).release(CREATOR_ID, MEMBER_ID, TOKEN);
    }

    @Test
    void 보정_락을_못_잡으면_아무것도_읽지_않고_false다() {
        when(lock.acquireCommon(MEMBER_ID)).thenReturn(null);

        assertThat(loader.load(CouponType.COMMON, CREATOR_ID, MEMBER_ID)).isFalse();

        verify(checker, never()).existsCommon(MEMBER_ID);
    }

    @Test
    void 적재_중_저장소_오류는_false로_삼키고_락은_푼다() {
        when(lock.acquireCommon(MEMBER_ID)).thenReturn(TOKEN);
        when(checker.existsCommon(MEMBER_ID)).thenThrow(new QueryTimeoutException("timeout"));

        assertThat(loader.load(CouponType.COMMON, CREATOR_ID, MEMBER_ID)).isFalse();

        verify(lock).releaseCommon(MEMBER_ID, TOKEN);
    }

    // 락 획득 자체가 Redis 오류로 실패해도 503(BALANCE_NOT_LOADED) 경로로 남아야 한다 - 500으로 새면 안 된다.
    @Test
    void 락_획득_중_저장소_오류는_false로_삼킨다() {
        when(lock.acquire(CREATOR_ID, MEMBER_ID)).thenThrow(new QueryTimeoutException("timeout"));

        assertThat(loader.load(CouponType.CREATOR, CREATOR_ID, MEMBER_ID)).isFalse();

        verify(checker, never()).exists(MEMBER_ID, CREATOR_ID);
    }

    // 락 해제 중 오류가 나도 이미 결정된 성공 결과(true)를 삼키면 안 된다.
    @Test
    void 락_해제_중_저장소_오류가_나도_적재_성공_결과는_유지한다() {
        when(lock.acquireCommon(MEMBER_ID)).thenReturn(TOKEN);
        when(checker.existsCommon(MEMBER_ID)).thenReturn(false);
        when(commonRepository.findById(MEMBER_ID)).thenReturn(Optional.empty());
        when(lock.loadCommonBalanceIfHeld(MEMBER_ID, TOKEN, 0L)).thenReturn(true);
        org.mockito.Mockito.doThrow(new QueryTimeoutException("timeout"))
                .when(lock).releaseCommon(MEMBER_ID, TOKEN);

        assertThat(loader.load(CouponType.COMMON, CREATOR_ID, MEMBER_ID)).isTrue();
    }

    // 시나리오 40: 미반영 메시지 검사가 길어 락이 만료되면(EARN 보정 등 다른 작업이 새로 락을 잡을 수 있는 상태)
    // 토큰이 더 이상 유효하지 않으므로 SET NX를 실행하지 않고 실패로 끝나야 한다.
    @Test
    void 검사_도중_락이_만료되면_적재하지_않고_false다() {
        when(lock.acquire(CREATOR_ID, MEMBER_ID)).thenReturn(TOKEN);
        when(checker.exists(MEMBER_ID, CREATOR_ID)).thenReturn(false);
        when(creatorRepository.findByMemberIdAndCreatorId(MEMBER_ID, CREATOR_ID)).thenReturn(Optional.of(
                UserTicketBalance.builder().memberId(MEMBER_ID).creatorId(CREATOR_ID).balance(7L)
                        .updatedAt(Instant.now()).build()));
        // loadBalanceIfHeld는 GET(락 토큰)==token 검증에 실패하면 false를 반환한다(TicketMaintenanceLock 원자 스크립트).
        when(lock.loadBalanceIfHeld(CREATOR_ID, MEMBER_ID, TOKEN, 7L)).thenReturn(false);

        assertThat(loader.load(CouponType.CREATOR, CREATOR_ID, MEMBER_ID)).isFalse();

        verify(lock).release(CREATOR_ID, MEMBER_ID, TOKEN);
    }
}
