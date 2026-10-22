package kr.co.cking.event.domain;

import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 공용 DB와 Spring 컨텍스트 없이 별도 MySQL에서 실제 flush·commit과 상품 ID를 검증한다. */
@Testcontainers(disabledWithoutDocker = true)
class EventPrizePersistenceTest {
    @Container
    private static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4.10")
            .withEnv("MYSQL_ROOT_PASSWORD", "test")
            .withEnv("MYSQL_DATABASE", "prize_test")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci")
            .withExposedPorts(3306)
            .waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    private static final Instant START = Instant.parse("2026-10-20T00:00:00Z");
    private static SessionFactory sessions;

    @BeforeAll
    static void createSchema() {
        sessions = new Configuration()
                .addAnnotatedClass(Event.class)
                .addAnnotatedClass(EventPrize.class)
                .setProperty("hibernate.connection.url", "jdbc:mysql://" + MYSQL.getHost() + ":"
                        + MYSQL.getMappedPort(3306) + "/prize_test")
                .setProperty("hibernate.connection.username", "root")
                .setProperty("hibernate.connection.password", "test")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("hibernate.show_sql", "true")
                .buildSessionFactory();
    }

    @AfterAll
    static void close() {
        if (sessions != null) sessions.close();
    }

    @ParameterizedTest(name = "상품 변경 시나리오 {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
    void 커밋_후_요청과_동일하고_유지한_ID와_다른_Event는_보존된다(int scenario) {
        PrizeConfig grand = new PrizeConfig("GRAND", "대상", 1, 10, 2);
        PrizeConfig second = new PrizeConfig("SECOND", "차상", 2, 1, 1);
        List<PrizeConfig> initial = List.of(grand, second);
        Long eventId = saveEvent(initial);
        Long otherId = saveEvent(initial);
        Map<String, Long> ids = ids(eventId);
        List<PrizeConfig> requested = switch (scenario) {
            case 1 -> List.of(new PrizeConfig("GRAND", "대상", 1, 10, 5), second);
            case 2 -> List.of(new PrizeConfig("GRAND", "대상", 1, 20, 2), second);
            case 3 -> List.of(grand, second, new PrizeConfig("NEW", "신규", 3, 1, 1));
            case 4 -> List.of(grand);
            case 5 -> List.of(new PrizeConfig("NEW", "신규", 1, 1, 1));
            case 6 -> List.of(grand, grand);
            case 7 -> initial;
            case 8 -> List.of(new PrizeConfig("GRAND", "변경", 2, 10, 2),
                    new PrizeConfig("SECOND", "차상", 1, 1, 1));
            case 9 -> List.of();
            case 10 -> List.of(new PrizeConfig("grand", "대상", 1, 10, 2), second);
            default -> throw new IllegalArgumentException();
        };

        if (scenario == 6) {
            assertThatThrownBy(() -> update(eventId, requested)).isInstanceOf(IllegalArgumentException.class);
            assertThat(read(eventId)).containsExactlyElementsOf(initial);
            assertThat(ids(eventId)).isEqualTo(ids);
        } else {
            update(eventId, requested);
            if (scenario == 7) update(eventId, requested);
            List<PrizeConfig> sorted = requested.stream()
                    .sorted(Comparator.comparingInt(PrizeConfig::priority).thenComparing(PrizeConfig::prizeKey))
                    .toList();
            assertThat(read(eventId)).containsExactlyElementsOf(sorted);
            Map<String, Long> after = ids(eventId);
            for (PrizeConfig config : requested) {
                ids.forEach((key, id) -> {
                    if (key.equalsIgnoreCase(config.prizeKey())) {
                        assertThat(after.get(config.prizeKey())).isEqualTo(id);
                    }
                });
            }
        }
        assertThat(read(otherId)).containsExactlyElementsOf(initial);
        assertThat(ids(eventId).values()).doesNotContainAnyElementsOf(ids(otherId).values());
    }

    private static Long saveEvent(List<PrizeConfig> prizes) {
        return sessions.fromTransaction(session -> {
            Event event = new Event(1L, "이벤트", null, START, START.plusSeconds(3600),
                    1, DrawMethod.WEIGHTED, 1L, UUID.randomUUID().toString(), prizes);
            session.persist(event);
            session.flush();
            return event.getEventId();
        });
    }

    private static void update(Long eventId, List<PrizeConfig> prizes) {
        sessions.inTransaction(session -> {
            session.find(Event.class, eventId).update("이벤트", null, START, START.plusSeconds(3600),
                    1, DrawMethod.WEIGHTED, prizes);
            session.flush();
        });
    }

    private static List<PrizeConfig> read(Long eventId) {
        return sessions.fromTransaction(session -> session.find(Event.class, eventId).getPrizeConfigs());
    }

    private static Map<String, Long> ids(Long eventId) {
        return sessions.fromTransaction(session -> session.find(Event.class, eventId).getPrizes().stream()
                .collect(Collectors.toMap(EventPrize::getPrizeKey, EventPrize::getId)));
    }
}
