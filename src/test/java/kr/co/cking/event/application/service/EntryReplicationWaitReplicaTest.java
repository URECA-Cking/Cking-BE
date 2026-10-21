package kr.co.cking.event.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** 주 노드·복제 노드 한 쌍에서 응모 Lua와 WAIT가 같은 연결로 나가는지 확인한다(#674). */
@Testcontainers(disabledWithoutDocker = true)
class EntryReplicationWaitReplicaTest {

    private static final DockerImageName REDIS = DockerImageName.parse("redis:7.2.16-alpine");
    private static final Network NETWORK = Network.newNetwork();

    @Container
    private static final GenericContainer<?> PRIMARY = new GenericContainer<>(REDIS)
            .withNetwork(NETWORK)
            .withNetworkAliases("primary")
            .withExposedPorts(6379);

    @Container
    private static final GenericContainer<?> REPLICA = new GenericContainer<>(REDIS)
            .withNetwork(NETWORK)
            .withCommand("redis-server", "--replicaof", "primary", "6379")
            .dependsOn(PRIMARY);

    private static final DefaultRedisScript<List> WRITE = script("redis.call('SET', KEYS[1], ARGV[1]) return {'SUCCESS'}");
    private static final DefaultRedisScript<List> REJECT = script("return {'INSUFFICIENT_BALANCE'}");

    private LettuceConnectionFactory sharedFactory;
    private final List<EntryReplicationWait> created = new ArrayList<>();

    @BeforeEach
    void setUp() throws InterruptedException {
        sharedFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(PRIMARY.getHost(), PRIMARY.getMappedPort(6379)),
                LettucePoolingClientConfiguration.defaultConfiguration());
        sharedFactory.afterPropertiesSet();
        sharedFactory.start();
        awaitReplicaOnline();
    }

    @AfterEach
    void tearDown() {
        created.forEach(EntryReplicationWait::destroy);
        sharedFactory.destroy();
    }

    @Test
    void 복제_노드가_받으면_확인됨으로_센다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EntryReplicationWait replicationWait = newWait(1_000, registry);

        List<?> result = replicationWait.execute(WRITE, List.of("replication:confirmed"), new Object[] {"v"}, 1L, 2L, "req-confirmed");

        assertThat(result).singleElement().isEqualTo("SUCCESS");
        assertThat(count(registry, "confirmed")).isEqualTo(1);
        assertThat(count(registry, "unconfirmed") + count(registry, "error")).isZero();
    }

    @Test
    void 복제_노드가_멈추면_제한_시간_뒤_결과를_그대로_돌려주고_확인_못함으로_센다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EntryReplicationWait replicationWait = newWait(300, registry);

        pauseReplica();
        long started = System.nanoTime();
        List<?> result;
        try {
            result = replicationWait.execute(WRITE, List.of("replication:paused"), new Object[] {"v"}, 1L, 2L, "req-paused");
        } finally {
            unpauseReplica();
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        // 같은 연결이 방금 쓴 것을 기다렸기 때문에 제한 시간까지 막혔다. 다른 연결이었다면 바로 확인됨이 나온다.
        assertThat(result).singleElement().isEqualTo("SUCCESS");
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(250));
        assertThat(count(registry, "unconfirmed")).isEqualTo(1);
        assertThat(count(registry, "confirmed") + count(registry, "error")).isZero();
    }

    @Test
    void SUCCESS가_아닌_결과는_기다리지_않는다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        EntryReplicationWait replicationWait = newWait(2_000, registry);

        pauseReplica();
        long started = System.nanoTime();
        List<?> result;
        try {
            result = replicationWait.execute(REJECT, List.of("replication:rejected"), new Object[0], 1L, 2L, "req-rejected");
        } finally {
            unpauseReplica();
        }

        assertThat(result).singleElement().isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        assertThat(count(registry, "confirmed") + count(registry, "unconfirmed") + count(registry, "error")).isZero();
    }

    private EntryReplicationWait newWait(long timeoutMillis, SimpleMeterRegistry registry) {
        EntryReplicationWait replicationWait = new EntryReplicationWait(sharedFactory, 1, timeoutMillis, registry);
        created.add(replicationWait);
        return replicationWait;
    }

    private long count(SimpleMeterRegistry registry, String result) {
        return registry.get(EntryReplicationWait.METRIC).tag("result", result).timer().count();
    }

    // 복제 연결이 맺어질 때까지 기다린다. 맺어지기 전에는 WAIT가 확인할 복제 노드가 없다.
    private void awaitReplicaOnline() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            Properties info = sharedFactory.getConnection().serverCommands().info("replication");
            String replica = info == null ? null : info.getProperty("slave0");
            if (replica != null && replica.contains("state=online")) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("복제 노드가 연결되지 않았습니다.");
    }

    private void pauseReplica() {
        DockerClientFactory.instance().client().pauseContainerCmd(REPLICA.getContainerId()).exec();
    }

    private void unpauseReplica() {
        DockerClientFactory.instance().client().unpauseContainerCmd(REPLICA.getContainerId()).exec();
    }

    private static DefaultRedisScript<List> script(String text) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>(text, List.class);
        return script;
    }
}
