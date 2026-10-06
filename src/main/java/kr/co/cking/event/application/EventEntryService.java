package kr.co.cking.event.application;

import java.time.Clock;
import java.time.Instant;
import kr.co.cking.abuse.application.EntryAbuseObserver;
import kr.co.cking.abuse.application.EntryObservationContext;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.event.application.dto.CachedEvent;
import kr.co.cking.event.application.dto.EntryCommand;
import kr.co.cking.event.application.dto.EntryOutcome;
import kr.co.cking.event.application.dto.EntrySpendResult;
import kr.co.cking.event.application.service.EntrySpendService;
import kr.co.cking.event.domain.EntryErrorCode;
import kr.co.cking.event.domain.EntryResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EventEntryService {

    private final EventQueryService eventQueryService;
    private final EntrySpendService entrySpendService;
    private final EntryAbuseObserver abuseObserver;
    private final Clock clock;

    public EntryOutcome apply(Long eventId, EntryCommand command) {
        CachedEvent event = eventQueryService.getCachedEvent(eventId);
        Instant requestedAt = clock.instant();
        EntryObservationContext observation = new EntryObservationContext(
                command.userId(), event.creatorId(), eventId, command.requestId(), command.couponType(), requestedAt);
        EntryOutcome outcome;
        try {
            outcome = applyToSpend(eventId, command, event);
        } catch (BusinessException exception) {
            abuseObserver.observeFailure(observation, exception.getErrorCode());
            throw exception;
        } catch (RuntimeException exception) {
            abuseObserver.observeUnexpectedFailure(observation);
            throw exception;
        }
        abuseObserver.observeSuccess(observation, outcome.code());
        return outcome;
    }

    /** Event 조회 뒤 기존 SPEND·응답 계약을 변경하지 않고 실행한다. */
    private EntryOutcome applyToSpend(Long eventId, EntryCommand command, CachedEvent event) {
        EntrySpendResult result = entrySpendService.spend(
                eventId,
                command.userId(),
                event.creatorId(),
                command.requestId().toString(),
                command.ticketCount(),
                command.couponType()
        );
        EntryResultCode code = EntryResultCode.valueOf(result.code().name());

        if (code != EntryResultCode.SUCCESS && code != EntryResultCode.DUPLICATE_REPLAY) {
            throw new BusinessException(EntryErrorCode.from(code));
        }
        return new EntryOutcome(code, command.requestId(), eventId);
    }
}
