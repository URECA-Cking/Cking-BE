package kr.co.cking.event.application;

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

    public EntryOutcome apply(Long eventId, EntryCommand command) {
        CachedEvent event = eventQueryService.getCachedEvent(eventId);
        EntrySpendResult result = entrySpendService.spend(
                eventId,
                command.userId(),
                event.creatorId(),
                command.requestId().toString(),
                command.ticketCount()
        );
        EntryResultCode code = EntryResultCode.valueOf(result.code().name());

        if (code != EntryResultCode.SUCCESS && code != EntryResultCode.DUPLICATE_REPLAY) {
            throw new BusinessException(EntryErrorCode.from(code));
        }
        return new EntryOutcome(code, command.requestId(), eventId);
    }
}
