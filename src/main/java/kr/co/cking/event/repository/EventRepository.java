package kr.co.cking.event.repository;

import kr.co.cking.event.domain.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {

    /** 생성 멱등성 식별자로 기존 Event를 조회한다. */
    Optional<Event> findByRequestId(String requestId);

    /** 상충하는 Event 명령을 직렬화하기 위해 행 잠금으로 Event를 조회한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Event> findByEventId(Long eventId);
}
