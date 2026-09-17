package kr.co.cking.event.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

import kr.co.cking.event.domain.EventEntry;

public interface EventEntryRepository extends JpaRepository<EventEntry, Long> {

    Optional<EventEntry> findByRequestId(String requestId);
}
