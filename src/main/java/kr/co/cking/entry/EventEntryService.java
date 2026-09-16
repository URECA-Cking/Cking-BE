package kr.co.cking.entry;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.event.CachedEvent;
import kr.co.cking.event.EventQueryService;
import kr.co.cking.event.application.dto.EntrySpendResult;
import kr.co.cking.event.application.service.EntrySpendService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EventEntryService {

    private final EventQueryService eventQueryService;
    private final EntrySpendService entrySpendService;

    public EntryOutcome apply(Long eventId, EntryRequest request) {
        CachedEvent event = eventQueryService.getCachedEvent(eventId);
        EntrySpendResult result = entrySpendService.spend(
                eventId,
                request.userId(),
                event.creatorId(),
                request.requestId().toString(),
                request.ticketCount()
        );
        EntryResultCode code = EntryResultCode.valueOf(result.code().name());

        if (code != EntryResultCode.SUCCESS && code != EntryResultCode.DUPLICATE_REPLAY) {
            throw new BusinessException(EntryErrorCode.from(code));
        }
        return new EntryOutcome(code, EntryResponse.accepted(request.requestId(), eventId));
    }
}
