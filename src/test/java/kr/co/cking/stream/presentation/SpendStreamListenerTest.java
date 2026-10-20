package kr.co.cking.stream.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.co.cking.stream.application.SpendRedeductionRefunder;
import kr.co.cking.ticket.application.TicketSpendLedgerService;
import kr.co.cking.ticket.application.dto.SpendApplyResult;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class SpendStreamListenerTest {

    private static final long ACCEPTED_AT = 1_792_000_000_000L;

    private final TicketSpendLedgerService ledgerService = mock(TicketSpendLedgerService.class);
    private final SpendRedeductionRefunder refunder = mock(SpendRedeductionRefunder.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final StreamOperations<String, Object, Object> streamOperations = mock(StreamOperations.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(ACCEPTED_AT + 1_500), ZoneOffset.UTC);
    private final SpendStreamListener listener = new SpendStreamListener(
            ledgerService, refunder, redisTemplate, meterRegistry, clock, "stream:test", "cg:test");

    @Test
    void DB에_반영하면_승인부터_반영까지_걸린_시간을_기록한다() {
        given(redisTemplate.opsForStream()).willReturn(streamOperations);
        given(ledgerService.apply(any(), any())).willReturn(SpendApplyResult.APPLIED);

        assertThat(listener.process(entry())).isTrue();

        Timer timer = applyDelay();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(1_500);
    }

    @Test
    void 이미_반영된_메시지가_재전달되면_ACK하고_기록하지_않는다() {
        given(redisTemplate.opsForStream()).willReturn(streamOperations);
        given(ledgerService.apply(any(), any())).willReturn(SpendApplyResult.ALREADY_APPLIED);

        assertThat(listener.process(entry())).isTrue();

        assertThat(applyDelay().count()).isZero();
    }

    @Test
    void 마감_barrier_메시지는_DB_반영이_없어_기록하지_않는다() {
        given(redisTemplate.opsForStream()).willReturn(streamOperations);
        MapRecord<String, String, String> barrier = StreamRecords.newRecord().in("stream:test")
                .withId(RecordId.of(ACCEPTED_AT, 0)).ofMap(Map.of("type", "EVENT_ENTRY_CLOSED", "eventId", "1"));

        assertThat(listener.process(barrier)).isTrue();

        assertThat(applyDelay().count()).isZero();
    }

    @Test
    void DB_반영에_실패하면_기록하지_않는다() {
        willThrow(new IllegalStateException("db down")).given(ledgerService).apply(any(), any());

        assertThat(listener.process(entry())).isFalse();

        assertThat(applyDelay().count()).isZero();
    }

    @Test
    void 다시_차감된_메시지는_되돌리기에_맡기고_그_결과를_돌려준다() {
        given(ledgerService.apply(any(), any())).willReturn(SpendApplyResult.REDEDUCTED);
        given(refunder.refundAndAcknowledge(any(), any())).willReturn(false);

        assertThat(listener.process(entry())).isFalse();

        verify(refunder).refundAndAcknowledge(any(), any());
        verify(redisTemplate, never()).opsForStream();
        assertThat(applyDelay().count()).isZero();
    }

    private MapRecord<String, String, String> entry() {
        return StreamRecords.newRecord().in("stream:test").withId(RecordId.of(ACCEPTED_AT, 0)).ofMap(Map.of(
                "eventId", "1", "userId", "2", "creatorId", "3",
                "requestId", "00000000-0000-4000-8000-000000000001", "ticketCount", "1", "couponType", "CREATOR"));
    }

    private Timer applyDelay() {
        return meterRegistry.get("cking.stream.apply.delay").tag("stream", "spend").timer();
    }
}
