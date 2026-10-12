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
