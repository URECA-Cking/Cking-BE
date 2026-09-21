package kr.co.cking.ticket.application;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import kr.co.cking.ticket.application.dto.EarnCommand;
import kr.co.cking.ticket.application.dto.EarnResult;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Redis 접근 예외를 EarnResultCode로 매핑하는 분기만 검증한다 - 실제 Redis 없이
 * StringRedisTemplate을 mock으로 대체해 타임아웃과 그 외 오류를 구분하는지 확인한다
 * (통합 API 명세 v2.5 §4.5: 명확한 오류=EARN_PROCESSING_FAILED, 타임아웃=EARN_STATUS_UNKNOWN).
 */
class TicketEarnServiceImplErrorMappingTest {

    private static EarnCommand command() {
        return new EarnCommand(
                UUID.randomUUID(), 1L, 900001L, "ATTENDANCE", 10L, "2026-09-16", "attendance:creator:2026-09-16", 1L);
    }

    @Test
    void 타임아웃이면_EARN_STATUS_UNKNOWN을_반환한다() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new QueryTimeoutException("Redis 응답 타임아웃"));
        TicketEarnServiceImpl service =
                new TicketEarnServiceImpl(redisTemplate, new DefaultRedisScript<List>(), "stream:ticket-earned:test", new ObjectMapper());

        EarnResult result = service.earn(command());

        assertThat(result.code()).isEqualTo(EarnResultCode.EARN_STATUS_UNKNOWN);
    }

    @Test
    void 타임아웃이_아닌_Redis_오류는_EARN_PROCESSING_FAILED를_반환한다() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisSystemException("Redis 연결 실패", new RuntimeException("connection refused")));
        TicketEarnServiceImpl service =
                new TicketEarnServiceImpl(redisTemplate, new DefaultRedisScript<List>(), "stream:ticket-earned:test", new ObjectMapper());

        EarnResult result = service.earn(command());

        assertThat(result.code()).isEqualTo(EarnResultCode.EARN_PROCESSING_FAILED);
    }
}
