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

/** 활성 템플릿 전환처럼 유일성이 필요한 명령을 MySQL advisory lock으로 직렬화한다. */
@Component
@Slf4j
@RequiredArgsConstructor
class CreatorSpaceTemplateLockManager {

    private static final String ACTIVATION_KEY = "creator-space-template:activation";

    private final JdbcTemplate jdbcTemplate;

    <T> T execute(Supplier<T> command) {
        Long acquired = jdbcTemplate.queryForObject("SELECT GET_LOCK(?, 0)", Long.class, ACTIVATION_KEY);
        if (acquired == null || acquired != 1L) {
            throw new BusinessException(CreatorErrorCode.CONCURRENT_COMMAND);
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            release();
            throw new IllegalStateException("Creator space template command requires a transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                release();
            }
        });
        return command.get();
    }

    private void release() {
        Long released = jdbcTemplate.queryForObject("SELECT RELEASE_LOCK(?)", Long.class, ACTIVATION_KEY);
        if (released == null || released != 1L) {
            log.warn("Failed to release creator space template advisory lock: result={}", released);
        }
    }
}
