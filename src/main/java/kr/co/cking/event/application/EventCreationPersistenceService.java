package kr.co.cking.event.application;

import kr.co.cking.event.application.dto.CreateEventCommand;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Event INSERT를 독립 트랜잭션에서 실행해 멱등 키 충돌을 호출자와 분리한다. */
@Service
@RequiredArgsConstructor
class EventCreationPersistenceService {
    private final EventRepository eventRepository;

    /** requestId UNIQUE 제약 위반을 즉시 확정하도록 Event를 flush한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Event create(CreateEventCommand command, Long creatorId) {
        return eventRepository.saveAndFlush(new Event(creatorId, command.title().trim(), command.description(),
                command.startAt(), command.endAt(), command.winnerCount(), command.drawMethod(), command.userId(), command.requestId()));
    }
}
