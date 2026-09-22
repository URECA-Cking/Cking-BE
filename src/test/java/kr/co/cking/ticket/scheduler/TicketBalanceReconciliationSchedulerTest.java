package kr.co.cking.ticket.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.Pageable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisException;
import kr.co.cking.common.config.SchedulingConfig;
import kr.co.cking.ticket.application.config.TicketRedisKeys;
import kr.co.cking.ticket.domain.UserTicketBalance;
import kr.co.cking.ticket.repository.UserTicketBalanceRepository;

/**
 * 비동기 반영 지연으로 인한 일시 불일치는 경고하지 않고, 연속된 주기에도 사라지지 않는
 * 불일치만 경고하는지 검증한다(취합v1.5.4 §13, 검증 시나리오 30번).
 */
class TicketBalanceReconciliationSchedulerTest {

    private final UserTicketBalanceRepository userTicketBalanceRepository = mock(UserTicketBalanceRepository.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

    private TicketBalanceReconciliationScheduler scheduler;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        scheduler = new TicketBalanceReconciliationScheduler(userTicketBalanceRepository, redisTemplate);

        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(TicketBalanceReconciliationScheduler.class)).addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(TicketBalanceReconciliationScheduler.class)).detachAppender(logAppender);
    }

    @Test
    void 기본_5분_주기의_스케줄러가_활성화돼_있다() throws NoSuchMethodException {
        Method reconcile = TicketBalanceReconciliationScheduler.class.getMethod("reconcile");
        Scheduled scheduled = reconcile.getAnnotation(Scheduled.class);
        ConditionalOnProperty enabled = SchedulingConfig.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(SchedulingConfig.class.isAnnotationPresent(EnableScheduling.class)).isTrue();
        assertThat(enabled).isNotNull();
        assertThat(enabled.matchIfMissing()).isTrue();
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${cking.ticket.reconciliation-interval-ms:300000}");
        assertThat(scheduled.timeUnit()).isEqualTo(TimeUnit.MILLISECONDS);
    }

    @Test
    void 첫_주기의_불일치는_WARN_없이_INFO로만_기록한다() {
        givenBalance(1L, 10L, 5L);
        givenRedisValue(1L, 10L, "3");

        scheduler.reconcile();

        assertThat(logAppender.list).noneMatch(event -> event.getLevel() == Level.WARN);
        assertThat(logAppender.list).anyMatch(event -> event.getLevel() == Level.INFO);
    }

    @Test
    void 두_주기_연속_불일치하면_WARN을_기록한다() {
        givenBalance(1L, 10L, 5L);
        givenRedisValue(1L, 10L, "3");
        scheduler.reconcile();

        givenBalance(1L, 10L, 5L);
        givenRedisValue(1L, 10L, "3");
        scheduler.reconcile();

        assertThat(logAppender.list).anyMatch(event -> event.getLevel() == Level.WARN);
    }

    @Test
    void Redis_키가_없고_DB_잔액이_있으면_유실로_보고_즉시_WARN을_남긴다() {
        givenBalance(1L, 10L, 5L);
        when(valueOperations.get(TicketRedisKeys.balance(10L, 1L))).thenReturn(null);

        scheduler.reconcile();

        assertThat(logAppender.list).hasSize(1)
                .allMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("Redis Balance 키가 없습니다"));
    }

    @Test
    void Redis_키가_없어도_DB_잔액이_0이면_로그를_남기지_않는다() {
        givenBalance(1L, 10L, 0L);
        when(valueOperations.get(TicketRedisKeys.balance(10L, 1L))).thenReturn(null);

        scheduler.reconcile();

        assertThat(logAppender.list).isEmpty();
    }

    @Test
    void 일치하면_추적_상태가_초기화돼_다시_일시_불일치부터_시작한다() {
        givenBalance(1L, 10L, 5L);
        givenRedisValue(1L, 10L, "3");
        scheduler.reconcile();

        givenBalance(1L, 10L, 5L);
        givenRedisValue(1L, 10L, "5");
        scheduler.reconcile();
        logAppender.list.clear();

        givenBalance(1L, 10L, 5L);
        givenRedisValue(1L, 10L, "3");
        scheduler.reconcile();

        assertThat(logAppender.list).noneMatch(event -> event.getLevel() == Level.WARN);
    }

    @Test
    void 특정_key의_Redis_값이_숫자가_아니어도_나머지_key_검사를_계속한다() {
        when(userTicketBalanceRepository.findNextBatch(anyLong(), anyLong(), any(Pageable.class)))
                .thenReturn(List.of(
                UserTicketBalance.builder().memberId(1L).creatorId(10L).balance(5L).build(),
                UserTicketBalance.builder().memberId(2L).creatorId(10L).balance(7L).build()));
        when(valueOperations.get(TicketRedisKeys.balance(10L, 1L))).thenReturn("not-a-number");
        givenRedisValue(2L, 10L, "3");

        scheduler.reconcile();

        assertThat(logAppender.list)
                .anyMatch(event -> event.getLevel() == Level.WARN && event.getFormattedMessage().contains("파싱"));
        assertThat(logAppender.list).anyMatch(event -> event.getLevel() == Level.INFO);
    }

    @Test
    void 파싱_실패로_한_주기를_건너뛰면_스트릭이_초기화돼_다시_INFO부터_시작한다() {
        givenBalance(1L, 10L, 5L);
        givenRedisValue(1L, 10L, "3");
        scheduler.reconcile();

        givenBalance(1L, 10L, 5L);
        when(valueOperations.get(TicketRedisKeys.balance(10L, 1L))).thenReturn("not-a-number");
        scheduler.reconcile();
        logAppender.list.clear();

        givenBalance(1L, 10L, 5L);
        givenRedisValue(1L, 10L, "3");
        scheduler.reconcile();

        assertThat(logAppender.list).noneMatch(event -> event.getLevel() == Level.WARN);
        assertThat(logAppender.list).anyMatch(event -> event.getLevel() == Level.INFO);
    }

    @Test
    void Redis_연결_자체가_실패하면_이번_주기를_중단하고_WARN_한_번만_남긴다() {
        when(userTicketBalanceRepository.findNextBatch(anyLong(), anyLong(), any(Pageable.class)))
                .thenReturn(List.of(
                UserTicketBalance.builder().memberId(1L).creatorId(10L).balance(5L).build(),
                UserTicketBalance.builder().memberId(2L).creatorId(10L).balance(7L).build()));
        when(valueOperations.get(TicketRedisKeys.balance(10L, 1L)))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        scheduler.reconcile();

        assertThat(logAppender.list.stream().filter(event -> event.getLevel() == Level.WARN)).hasSize(1);
        verify(valueOperations, never()).get(TicketRedisKeys.balance(10L, 2L));
    }

    @Test
    void Redis_연결_실패로_한_주기를_건너뛰면_전체_스트릭이_초기화돼_다시_INFO부터_시작한다() {
        givenBalance(1L, 10L, 5L);
        givenRedisValue(1L, 10L, "3");
        scheduler.reconcile();

        when(userTicketBalanceRepository.findNextBatch(anyLong(), anyLong(), any(Pageable.class)))
                .thenReturn(List.of(
                UserTicketBalance.builder().memberId(1L).creatorId(10L).balance(5L).build()));
        when(valueOperations.get(TicketRedisKeys.balance(10L, 1L)))
                .thenThrow(new RedisConnectionFailureException("connection refused"));
        scheduler.reconcile();
        logAppender.list.clear();

        givenBalance(1L, 10L, 5L);
        // 이전 스텁이 thenThrow였으므로 when(...).thenReturn(...)으로 재스텁하면 재스텁 과정에서
        // 옛 스텁(예외)이 먼저 실행된다 - doReturn().when(...)으로 안전하게 교체한다.
        org.mockito.Mockito.doReturn("3").when(valueOperations).get(TicketRedisKeys.balance(10L, 1L));
        scheduler.reconcile();

        assertThat(logAppender.list).noneMatch(event -> event.getLevel() == Level.WARN);
        assertThat(logAppender.list).anyMatch(event -> event.getLevel() == Level.INFO);
    }

    @Test
    void WRONGTYPE처럼_원인이_RedisCommandExecutionException이면_key_단위_오류로_다음_key를_계속_검사한다() {
        when(userTicketBalanceRepository.findNextBatch(anyLong(), anyLong(), any(Pageable.class)))
                .thenReturn(List.of(
                UserTicketBalance.builder().memberId(1L).creatorId(10L).balance(5L).build(),
                UserTicketBalance.builder().memberId(2L).creatorId(10L).balance(7L).build()));
        when(valueOperations.get(TicketRedisKeys.balance(10L, 1L))).thenThrow(
                new RedisSystemException("Error in execution", new RedisCommandExecutionException("WRONGTYPE")));
        givenRedisValue(2L, 10L, "3");

        scheduler.reconcile();

        assertThat(logAppender.list.stream().filter(event -> event.getLevel() == Level.WARN)).hasSize(1);
        assertThat(logAppender.list).anyMatch(event -> event.getLevel() == Level.INFO);
        verify(valueOperations).get(TicketRedisKeys.balance(10L, 2L));
    }

    @Test
    void RedisSystemException의_원인이_일반_RedisException이면_연결장애로_보고_다음_key를_검사하지_않는다() {
        when(userTicketBalanceRepository.findNextBatch(anyLong(), anyLong(), any(Pageable.class)))
                .thenReturn(List.of(
                UserTicketBalance.builder().memberId(1L).creatorId(10L).balance(5L).build(),
                UserTicketBalance.builder().memberId(2L).creatorId(10L).balance(7L).build()));
        when(valueOperations.get(TicketRedisKeys.balance(10L, 1L))).thenThrow(
                new RedisSystemException("Redis exception", new RedisException("Connection closed")));

        scheduler.reconcile();

        assertThat(logAppender.list.stream().filter(event -> event.getLevel() == Level.WARN)).hasSize(1);
        verify(valueOperations, never()).get(TicketRedisKeys.balance(10L, 2L));
    }

    @Test
    void QueryTimeoutException_발생시_전체_스트릭이_초기화돼_다음_정상_주기_불일치는_INFO부터_시작한다() {
        givenBalance(1L, 10L, 5L);
        givenRedisValue(1L, 10L, "3");
        scheduler.reconcile();

        givenBalance(1L, 10L, 5L);
        when(valueOperations.get(TicketRedisKeys.balance(10L, 1L)))
                .thenThrow(new QueryTimeoutException("Redis command timed out"));
        scheduler.reconcile();
        logAppender.list.clear();

        givenBalance(1L, 10L, 5L);
        org.mockito.Mockito.doReturn("3").when(valueOperations).get(TicketRedisKeys.balance(10L, 1L));
        scheduler.reconcile();

        assertThat(logAppender.list).noneMatch(event -> event.getLevel() == Level.WARN);
        assertThat(logAppender.list).anyMatch(event -> event.getLevel() == Level.INFO);
    }

    @Test
    void 한_배치가_가득_차면_마지막_키_이후부터_다음_배치를_조회한다() {
        List<UserTicketBalance> full = new java.util.ArrayList<>();
        for (long memberId = 1; memberId <= 500; memberId++) {
            full.add(UserTicketBalance.builder().memberId(memberId).creatorId(10L).balance(5L).build());
        }
        UserTicketBalance next = UserTicketBalance.builder().memberId(501L).creatorId(10L).balance(7L).build();
        when(userTicketBalanceRepository.findNextBatch(anyLong(), anyLong(), any(Pageable.class)))
                .thenReturn(full)
                .thenReturn(List.of(next));
        givenRedisValue(501L, 10L, "7");

        scheduler.reconcile();

        ArgumentCaptor<Long> memberIds = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> creatorIds = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Pageable> pageables = ArgumentCaptor.forClass(Pageable.class);
        verify(userTicketBalanceRepository, times(2))
                .findNextBatch(memberIds.capture(), creatorIds.capture(), pageables.capture());
        // 첫 조회는 처음부터, 두 번째는 첫 배치의 마지막 키(500, 10) 이후부터 읽는다.
        assertThat(memberIds.getAllValues()).containsExactly(Long.MIN_VALUE, 500L);
        assertThat(creatorIds.getAllValues()).containsExactly(Long.MIN_VALUE, 10L);
        assertThat(pageables.getAllValues()).extracting(Pageable::getPageNumber).containsOnly(0);
        assertThat(pageables.getAllValues()).extracting(Pageable::getPageSize).containsOnly(500);
        verify(valueOperations).get(TicketRedisKeys.balance(10L, 501L));
    }

    private void givenBalance(Long memberId, Long creatorId, Long balance) {
        when(userTicketBalanceRepository.findNextBatch(anyLong(), anyLong(), any(Pageable.class)))
                .thenReturn(List.of(
                UserTicketBalance.builder().memberId(memberId).creatorId(creatorId).balance(balance).build()));
    }

    private void givenRedisValue(Long memberId, Long creatorId, String value) {
        when(valueOperations.get(TicketRedisKeys.balance(creatorId, memberId))).thenReturn(value);
    }
}
