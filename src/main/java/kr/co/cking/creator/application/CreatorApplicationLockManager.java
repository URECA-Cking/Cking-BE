package kr.co.cking.creator.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.domain.CreatorErrorCode;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.function.Supplier;

@Component
@Slf4j
@RequiredArgsConstructor
class CreatorApplicationLockManager {

    private static final String ACTIVITY_NAME_ALLOCATION_LOCK = "creator-activity-name:allocation";
    private static final int ACTIVITY_NAME_ALLOCATION_WAIT_SECONDS = 15;
    private static final ReentrantLock[] LOCAL_REWARD_LOCKS = IntStream.range(0, 256)
            .mapToObj(ignored -> new ReentrantLock()).toArray(ReentrantLock[]::new);

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    /** 별도 DB 트랜잭션을 여는 보상 서비스가 사용할, 연결 소유권이 명확한 세션 잠금. */
    <T> T executeWithoutTransaction(String key, int waitSeconds, Supplier<T> command) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Reward admission lock must not join a transaction");
        }
        // 대기 중인 동일 Creator 요청이 커넥션 풀을 모두 점유하면 선행 요청의
        // REQUIRES_NEW 보상 트랜잭션도 커넥션을 얻지 못한다. 로컬 대기는 연결 밖에서 한다.
        ReentrantLock localLock = LOCAL_REWARD_LOCKS[Math.floorMod(key.hashCode(), LOCAL_REWARD_LOCKS.length)];
        localLock.lock();
        try {
            try (Connection connection = dataSource.getConnection()) {
                if (namedLock(connection, "SELECT GET_LOCK(?, ?)", key, waitSeconds) != 1L) {
                    throw new BusinessException(CreatorErrorCode.CONCURRENT_COMMAND);
                }
                try {
                    return command.get();
                } finally {
                    if (namedLock(connection, "SELECT RELEASE_LOCK(?)", key, null) != 1L) {
                        log.warn("Failed to release creator reward advisory lock: key={}", key);
                    }
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Creator reward advisory lock failed", exception);
            }
        } finally {
            localLock.unlock();
        }
    }

    private long namedLock(Connection connection, String sql, String key, Integer waitSeconds) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            if (waitSeconds != null) statement.setInt(2, waitSeconds);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        }
    }

    <T> T execute(String key, Supplier<T> command) {
        return execute(key, 0, "SELECT GET_LOCK(?, 0)", command);
    }

    /**
     * 같은 공개 활동명을 두 명이 동시에 선택해도, 먼저 끝난 트랜잭션의 결과를 다시 확인한 뒤
     * 다음 후보를 선택하도록 이름 할당만 짧게 대기한다.
     */
    <T> T executeWaiting(String key, int waitSeconds, Supplier<T> command) {
        return execute(key, waitSeconds, "SELECT GET_LOCK(?, ?)", command);
    }

    /**
     * Creator 승인에서 첫 JPA 조회 전에 전역 활동명 lock을 잡는다. 대기 중 다른 승인이 commit한
     * 활동명이 이후 REPEATABLE READ snapshot에 포함되므로, 최신 상태를 기준으로 접미사 후보를 고른다.
     */
    <T> T executeActivityNameAllocation(Supplier<T> command) {
        return executeWaiting(ACTIVITY_NAME_ALLOCATION_LOCK, ACTIVITY_NAME_ALLOCATION_WAIT_SECONDS, command);
    }

    private <T> T execute(String key, int waitSeconds, String query, Supplier<T> command) {
        Long acquired = waitSeconds == 0
                ? jdbcTemplate.queryForObject(query, Long.class, key)
                : jdbcTemplate.queryForObject(query, Long.class, key, waitSeconds);
        if (acquired == null || acquired != 1L) {
            throw new BusinessException(CreatorErrorCode.CONCURRENT_COMMAND);
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            release(key);
            throw new IllegalStateException("Creator application command requires a transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                release(key);
            }
        });
        try {
            return command.get();
        } catch (RuntimeException e) {
            throw e;
        }
    }

    private void release(String key) {
        Long released = jdbcTemplate.queryForObject("SELECT RELEASE_LOCK(?)", Long.class, key);
        if (released == null || released != 1L) {
            log.warn("Failed to release creator application advisory lock: key={}, result={}", key, released);
        }
    }
}
