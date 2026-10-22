package kr.co.cking.post.scheduler;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.co.cking.post.filter.CommentFilterDispatcher;
import kr.co.cking.post.filter.CommentFilterProperties;
import kr.co.cking.post.filter.CommentFilterRetryService;
import kr.co.cking.post.repository.CommentFilterRetryCandidate;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CommentFilterRetrySchedulerTest {

    private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");
    private static final Instant UPDATED_AT = NOW.minus(Duration.ofHours(1));

    private final CommentFilterRetryService retryService = mock(CommentFilterRetryService.class);
    private final CommentFilterDispatcher dispatcher = mock(CommentFilterDispatcher.class);
    private final CommentFilterProperties properties = new CommentFilterProperties();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final CommentFilterRetryScheduler scheduler = new CommentFilterRetryScheduler(
            retryService, dispatcher, properties, Clock.fixed(NOW, ZoneOffset.UTC), meters);

    @Test
    void 대기_시간이_지난_대상을_선점한_뒤_제출한다() {
        CommentFilterRetryCandidate first = candidate(1L, 0);
        CommentFilterRetryCandidate second = candidate(2L, 2);
        givenCandidates(first, second);
        given(retryService.claim(any(), any())).willReturn(true);

        scheduler.retry();

        verify(retryService).findCandidates(NOW, NOW.minus(Duration.ofMinutes(1)), 5, 20);
        // 이미 재제출한 횟수에 따라 다음 시도 시각이 base, 2*base, 4*base로 늘어난다.
        verify(retryService).claim(first, NOW.plus(Duration.ofMinutes(1)));
        verify(retryService).claim(second, NOW.plus(Duration.ofMinutes(4)));
        verify(dispatcher).dispatch(1L);
        verify(dispatcher).dispatch(2L);
        assertThat(counter("attempted")).isEqualTo(2);
    }

    @Test
    void 한_번에_가져오는_건수는_배치_크기와_큐의_남은_자리_중_작은_값이다() {
        given(dispatcher.remainingQueueCapacity()).willReturn(7);
        given(retryService.findCandidates(any(), any(), anyInt(), anyInt())).willReturn(List.of());

        scheduler.retry();

        verify(retryService).findCandidates(NOW, NOW.minus(Duration.ofMinutes(1)), 5, 7);
    }

    @Test
    void 큐에_자리가_없으면_조회도_제출도_하지_않는다() {
        given(dispatcher.remainingQueueCapacity()).willReturn(0);

        scheduler.retry();

        verify(retryService, never()).findCandidates(any(), any(), anyInt(), anyInt());
        verify(dispatcher, never()).dispatch(any());
        assertThat(counter("queue_full")).isEqualTo(1);
    }

    @Test
    void 선점에_실패한_댓글은_제출하지_않는다() {
        givenCandidates(candidate(1L, 0));
        given(retryService.claim(any(), any())).willReturn(false);

        scheduler.retry();

        verify(dispatcher, never()).dispatch(any());
        assertThat(counter("attempted")).isZero();
    }

    @Test
    void 마지막_시도를_제출했을_때만_상한_소진을_센다() {
        givenCandidates(candidate(1L, 3), candidate(2L, 4));
        given(retryService.claim(any(), any())).willReturn(true);

        scheduler.retry();

        // 횟수가 3인 댓글의 이번 시도는 4번째이므로 아직 상한(5)이 아니고, 4인 댓글의 이번 시도가 5번째다.
        assertThat(counter("exhausted")).isEqualTo(1);
    }

    @Test
    void 큐가_가득_차_제출이_거절되면_선점을_되돌리고_나머지_후보는_처리하지_않는다() {
        CommentFilterRetryCandidate first = candidate(1L, 0);
        CommentFilterRetryCandidate second = candidate(2L, 0);
        givenCandidates(first, second);
        given(retryService.claim(any(), any())).willReturn(true);
        doThrow(new TaskRejectedException("full")).when(dispatcher).dispatch(1L);

        scheduler.retry();

        verify(retryService).release(first);
        verify(retryService, never()).claim(eq(second), any());
        verify(dispatcher, never()).dispatch(2L);
        // 필터가 실행되지 않았으므로 시도로 세지 않고, 마지막 시도로 기록하지도 않는다.
        assertThat(counter("attempted")).isZero();
        assertThat(counter("exhausted")).isZero();
        assertThat(counter("queue_full")).isEqualTo(1);
    }

    @Test
    void 마지막_시도가_큐_거절로_제출되지_못하면_상한_소진으로_기록하지_않는다() {
        CommentFilterRetryCandidate last = candidate(1L, 4);
        givenCandidates(last);
        given(retryService.claim(any(), any())).willReturn(true);
        doThrow(new TaskRejectedException("full")).when(dispatcher).dispatch(1L);

        scheduler.retry();

        verify(retryService).release(last);
        assertThat(counter("exhausted")).isZero();
    }

    @Test
    void 예기치_못한_제출_실패는_선점을_되돌리고_나머지를_계속_처리한다() {
        CommentFilterRetryCandidate first = candidate(1L, 0);
        CommentFilterRetryCandidate second = candidate(2L, 0);
        givenCandidates(first, second);
        given(retryService.claim(any(), any())).willReturn(true);
        doThrow(new IllegalStateException("boom")).when(dispatcher).dispatch(1L);

        scheduler.retry();

        verify(retryService).release(first);
        verify(dispatcher).dispatch(2L);
        assertThat(counter("submit_failed")).isEqualTo(1);
        assertThat(counter("attempted")).isEqualTo(1);
    }

    @Test
    void 선점을_되돌리지_못해도_예외를_밖으로_내지_않는다() {
        CommentFilterRetryCandidate first = candidate(1L, 0);
        givenCandidates(first);
        given(retryService.claim(any(), any())).willReturn(true);
        doThrow(new TaskRejectedException("full")).when(dispatcher).dispatch(1L);
        doThrow(new IllegalStateException("db down")).when(retryService).release(first);

        scheduler.retry();

        verify(retryService).release(first);
    }

    private void givenCandidates(CommentFilterRetryCandidate... candidates) {
        given(dispatcher.remainingQueueCapacity()).willReturn(100);
        given(retryService.findCandidates(any(), any(), anyInt(), anyInt())).willReturn(List.of(candidates));
    }

    private static CommentFilterRetryCandidate candidate(Long commentId, int attempts) {
        return new CommentFilterRetryCandidate(commentId, attempts, UPDATED_AT, null);
    }

    private double counter(String name) {
        return meters.counter("comment_filter.retry." + name).count();
    }
}
