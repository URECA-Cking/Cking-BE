package kr.co.cking.event.application;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.event.domain.EventErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.function.Supplier;
/** Event 생성 멱등 키별 동시 명령을 직렬화한다. */
@Component @RequiredArgsConstructor
class EventCreationLockManager {
    private final JdbcTemplate jdbcTemplate;
    /** 잠금을 획득한 뒤 트랜잭션 완료 시점까지 명령을 실행한다. */
    <T> T execute(String key, Supplier<T> command) {
        Long acquired = jdbcTemplate.queryForObject("SELECT GET_LOCK(?, 5)", Long.class, key);
        if (acquired == null || acquired != 1L) throw new BusinessException(EventErrorCode.CONCURRENT_COMMAND);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /** 트랜잭션 완료 후 동일 연결의 advisory lock을 해제한다. */
            @Override public void afterCompletion(int status) { jdbcTemplate.queryForObject("SELECT RELEASE_LOCK(?)", Long.class, key); }
        });
        return command.get();
    }
}
