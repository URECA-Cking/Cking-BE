package kr.co.cking.event.application.service;

import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import kr.co.cking.event.application.dto.enums.EntrySpendResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 응모 Lua와 WAIT를 같은 전용 Redis 연결에서 보내, 성공 응답 전에 복제 노드가 그 응모를 받았는지 확인한다(#674).
 * WAIT는 같은 연결이 보낸 쓰기만 확인하고 그동안 연결을 막는다. 공유 연결에서 부르면 모든 Redis 명령이 줄을 서므로
 * 공유하지 않는 연결 팩토리를 따로 둔다.
 * 제한 시간 안에 확인하지 못해도 Lua 결과를 그대로 돌려준다. 복제 노드 장애 때 응모 전체가 막히지 않게 하고,
 * 확인하지 못한 응모는 로그로 남겨 장애 뒤 DB와 대조한다.
 */
@Slf4j
public class EntryReplicationWait implements DisposableBean {

    static final String METRIC = "cking.entry.replication.wait";
    // WAIT는 제한 시간이 지나면 응답한다. 그 응답이 오는 동안의 네트워크 여유다.
    private static final long RESPONSE_MARGIN_MILLIS = 1_000;

    private final LettuceConnectionFactory connectionFactory;
    private final StringRedisTemplate redisTemplate;
    private final int replicas;
    private final long timeoutMillis;
    private final Timer confirmed;
    private final Timer unconfirmed;
    private final Timer failed;

    public EntryReplicationWait(LettuceConnectionFactory sharedFactory, int replicas, long timeoutMillis, MeterRegistry meterRegistry) {
        if (replicas < 1 || timeoutMillis < 1) {
            // WAIT의 제한 시간 0은 무한 대기다.
            throw new IllegalStateException("응모 복제 확인의 복제 노드 수와 제한 시간은 1 이상이어야 합니다. replicas=%d, timeoutMillis=%d"
                    .formatted(replicas, timeoutMillis));
        }
        this.connectionFactory = dedicatedFactory(sharedFactory);
        this.redisTemplate = new StringRedisTemplate(connectionFactory);
        this.replicas = replicas;
        this.timeoutMillis = timeoutMillis;
        this.confirmed = Timer.builder(METRIC).tag("result", "confirmed").register(meterRegistry);
        this.unconfirmed = Timer.builder(METRIC).tag("result", "unconfirmed").register(meterRegistry);
        this.failed = Timer.builder(METRIC).tag("result", "error").register(meterRegistry);
    }

    /** Lua를 실행하고, 결과가 SUCCESS면 같은 연결에서 복제 노드 반영을 기다린 뒤 Lua 결과를 돌려준다. */
    public List<?> execute(
            RedisScript<List> script, List<String> keys, Object[] args, Long eventId, Long userId, String requestId
    ) {
        return redisTemplate.execute(new SessionCallback<List<?>>() {
            @Override
            public <K, V> List<?> execute(RedisOperations<K, V> operations) {
                // SessionCallback 안에서는 같은 연결이 묶여 있어, 아래 Lua와 WAIT가 한 연결로 나간다.
                List<?> result = redisTemplate.execute(script, keys, args);
                if (isSuccess(result)) {
                    waitForReplicas(eventId, userId, requestId);
                }
                return result;
            }
        });
    }

    private void waitForReplicas(Long eventId, Long userId, String requestId) {
        long started = System.nanoTime();
        Long acknowledged;
        try {
            acknowledged = redisTemplate.execute((RedisCallback<Long>) this::waitOnSameConnection);
        } catch (DataAccessException exception) {
            failed.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            log.warn("응모를 복제 노드에서 확인하지 못했습니다(명령 실패). eventId={}, userId={}, requestId={}",
                    eventId, userId, requestId, exception);
            return;
        }
        long elapsed = System.nanoTime() - started;
        if (acknowledged != null && acknowledged >= replicas) {
            confirmed.record(elapsed, TimeUnit.NANOSECONDS);
            return;
        }
        unconfirmed.record(elapsed, TimeUnit.NANOSECONDS);
        log.warn("응모를 복제 노드에서 확인하지 못했습니다(시간 초과). eventId={}, userId={}, requestId={}, acknowledged={}",
                eventId, userId, requestId, acknowledged);
    }

    // 일반 execute는 응답을 바이트로 읽어 WAIT의 정수 응답을 받지 못해 Lettuce의 WAIT를 쓴다. 이 팩토리는 연결을
    // 공유하지 않아, 네이티브 연결은 방금 Lua를 보낸 전용 연결이다.
    @SuppressWarnings("unchecked")
    private Long waitOnSameConnection(RedisConnection connection) {
        RedisClusterAsyncCommands<byte[], byte[]> commands =
                (RedisClusterAsyncCommands<byte[], byte[]>) connection.getNativeConnection();
        try {
            return commands.waitForReplication(replicas, timeoutMillis)
                    .get(timeoutMillis + RESPONSE_MARGIN_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RedisSystemException("WAIT 응답을 기다리다 중단됐습니다.", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new RedisSystemException("WAIT가 실패했습니다.", exception);
        }
    }

    private static boolean isSuccess(List<?> result) {
        return result != null && !result.isEmpty()
                && EntrySpendResultCode.SUCCESS.name().equals(String.valueOf(result.get(0)));
    }

    // 공유 팩토리와 같은 주소·TLS·풀 설정으로 만들되, 연결을 공유하지 않는다.
    private static LettuceConnectionFactory dedicatedFactory(LettuceConnectionFactory sharedFactory) {
        if (sharedFactory.getClusterConfiguration() != null || sharedFactory.getSentinelConfiguration() != null) {
            throw new IllegalStateException("응모 복제 확인은 주 노드 하나에 붙는 구성에서만 쓴다.");
        }
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                sharedFactory.getStandaloneConfiguration(), sharedFactory.getClientConfiguration());
        factory.setShareNativeConnection(false);
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }


    @Override
    public void destroy() {
        connectionFactory.destroy();
    }
}
