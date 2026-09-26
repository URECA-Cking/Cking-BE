package kr.co.cking.ticket.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import kr.co.cking.stream.application.UnappliedBalanceMessageChecker;
import kr.co.cking.ticket.application.config.CommonTicketRedisKeys;
import kr.co.cking.ticket.application.config.TicketRedisKeys;
import kr.co.cking.ticket.domain.CouponType;
import kr.co.cking.ticket.domain.UserCommonTicketBalance;
import kr.co.cking.ticket.domain.UserTicketBalance;
import kr.co.cking.ticket.repository.UserCommonTicketBalanceRepository;
import kr.co.cking.ticket.repository.UserTicketBalanceRepository;

class TicketBalanceKeyLoaderTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long CREATOR_ID = 2L;
    private static final String TOKEN = "token";

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final TicketMaintenanceLock lock = mock(TicketMaintenanceLock.class);
    private final UnappliedBalanceMessageChecker checker = mock(UnappliedBalanceMessageChecker.class);
    private final UserTicketBalanceRepository creatorRepository = mock(UserTicketBalanceRepository.class);
    private final UserCommonTicketBalanceRepository commonRepository = mock(UserCommonTicketBalanceRepository.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);

    private TicketBalanceKeyLoader loader;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(values);
        loader = new TicketBalanceKeyLoader(redisTemplate, lock, checker, creatorRepository, commonRepository);
    }

    @Test
    void CREATOR는_DB_잔액으로_SET_NX_적재하고_락을_푼다() {
        when(lock.acquire(CREATOR_ID, MEMBER_ID)).thenReturn(TOKEN);
        when(checker.exists(MEMBER_ID, CREATOR_ID)).thenReturn(false);
        when(creatorRepository.findByMemberIdAndCreatorId(MEMBER_ID, CREATOR_ID)).thenReturn(Optional.of(
                UserTicketBalance.builder().memberId(MEMBER_ID).creatorId(CREATOR_ID).balance(7L)
                        .updatedAt(Instant.now()).build()));

        assertThat(loader.load(CouponType.CREATOR, CREATOR_ID, MEMBER_ID)).isTrue();

        verify(values).setIfAbsent(TicketRedisKeys.balance(CREATOR_ID, MEMBER_ID), "7");
        verify(lock).release(CREATOR_ID, MEMBER_ID, TOKEN);
    }

    @Test
    void COMMON은_DB_잔액으로_적재하고_행이_없으면_0을_적재한다() {
        when(lock.acquireCommon(MEMBER_ID)).thenReturn(TOKEN);
        when(checker.existsCommon(MEMBER_ID)).thenReturn(false);
        when(commonRepository.findById(MEMBER_ID)).thenReturn(Optional.empty());

        assertThat(loader.load(CouponType.COMMON, CREATOR_ID, MEMBER_ID)).isTrue();

        verify(values).setIfAbsent(CommonTicketRedisKeys.balance(MEMBER_ID), "0");
        verify(lock).releaseCommon(MEMBER_ID, TOKEN);
    }

    @Test
    void COMMON_DB_잔액이_있으면_그_값으로_적재한다() {
        when(lock.acquireCommon(MEMBER_ID)).thenReturn(TOKEN);
        when(commonRepository.findById(MEMBER_ID)).thenReturn(Optional.of(
                UserCommonTicketBalance.builder().memberId(MEMBER_ID).balance(4L).updatedAt(Instant.now()).build()));

        assertThat(loader.load(CouponType.COMMON, CREATOR_ID, MEMBER_ID)).isTrue();

        verify(values).setIfAbsent(CommonTicketRedisKeys.balance(MEMBER_ID), "4");
    }

    @Test
    void 미반영_메시지가_있으면_적재하지_않고_락은_푼다() {
        when(lock.acquire(CREATOR_ID, MEMBER_ID)).thenReturn(TOKEN);
        when(checker.exists(MEMBER_ID, CREATOR_ID)).thenReturn(true);

        assertThat(loader.load(CouponType.CREATOR, CREATOR_ID, MEMBER_ID)).isFalse();

        verify(values, never()).setIfAbsent(anyString(), anyString());
        verify(lock).release(CREATOR_ID, MEMBER_ID, TOKEN);
    }

    @Test
    void 보정_락을_못_잡으면_아무것도_읽지_않고_false다() {
        when(lock.acquireCommon(MEMBER_ID)).thenReturn(null);

        assertThat(loader.load(CouponType.COMMON, CREATOR_ID, MEMBER_ID)).isFalse();

        verify(checker, never()).existsCommon(MEMBER_ID);
        verify(values, never()).setIfAbsent(anyString(), anyString());
    }

    @Test
    void 적재_중_저장소_오류는_false로_삼키고_락은_푼다() {
        when(lock.acquireCommon(MEMBER_ID)).thenReturn(TOKEN);
        when(checker.existsCommon(MEMBER_ID)).thenThrow(new QueryTimeoutException("timeout"));

        assertThat(loader.load(CouponType.COMMON, CREATOR_ID, MEMBER_ID)).isFalse();

        verify(lock).releaseCommon(MEMBER_ID, TOKEN);
    }
}
