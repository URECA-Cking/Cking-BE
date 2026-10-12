package kr.co.cking.creator.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.domain.CreatorErrorCode;
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

    private final JdbcTemplate jdbcTemplate;

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
