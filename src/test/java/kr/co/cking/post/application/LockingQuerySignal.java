package kr.co.cking.post.application;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * 테스트 전용 Hibernate {@link StatementInspector}. 기다리는 잠금 조회 SQL이 DB로 전송되기 직전에 신호를 보낸다.
 *
 * <p>DB 내부 잠금 상태(information_schema 등)를 조회하지 않고도, 요청이 스레드 시작이 아니라 해당 잠금 조회에 실제로
 * 진입했음을 확인하기 위해 쓴다. 잠금 조회를 일반 조회로 바꾸면 SQL에 잠금 절이 없어 신호가 오지 않는다.
 */
public class LockingQuerySignal implements StatementInspector {

    private static final Map<String, CountDownLatch> EXPECTED = new ConcurrentHashMap<>();

    /** 이 시점 이후 {@code from <table> … <lockClause>} SQL이 실행되면 열리는 latch를 등록한다. */
    static CountDownLatch expect(String table, String lockClause) {
        CountDownLatch latch = new CountDownLatch(1);
        EXPECTED.put(key(table, lockClause), latch);
        return latch;
    }

    static void clear() {
        EXPECTED.clear();
    }

    @Override
    public String inspect(String sql) {
        if (EXPECTED.isEmpty()) {
            return sql;
        }
        String normalized = sql.toLowerCase(Locale.ROOT);
        EXPECTED.forEach((key, latch) -> {
            String[] parts = key.split("\\|", 2);
            if (normalized.contains("from " + parts[0] + " ") && normalized.contains(parts[1])) {
                latch.countDown();
            }
        });
        return sql;
    }

    private static String key(String table, String lockClause) {
        return table + "|" + lockClause;
    }
}
