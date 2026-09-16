package kr.co.cking.event.repository;

import kr.co.cking.event.domain.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import jakarta.persistence.LockModeType;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface EventRepository extends JpaRepository<Event, Long> {

    /** 생성 멱등성 식별자로 기존 Event를 조회한다. */
    Optional<Event> findByRequestId(String requestId);

    /** 상충하는 Event 명령을 직렬화하기 위해 행 잠금으로 Event를 조회한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Event> findByEventId(Long eventId);

    /** Creator의 삭제되지 않은 Event를 생성 역순으로 페이지 조회한다. */
    Page<Event> findByCreatorIdAndDeletedAtIsNullOrderByCreatedAtDescEventIdDesc(Long creatorId, Pageable pageable);
}
