package kr.co.cking.entry;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.event.CachedEvent;
import kr.co.cking.event.EventQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EventEntryService {

    private final EventQueryService eventQueryService;
    private final EntryGateway entryGateway;

    public EntryOutcome apply(Long eventId, EntryRequest request) {
        CachedEvent event = eventQueryService.getCachedEvent(eventId);
        EntryResultCode result = entryGateway.apply(eventId, event.creatorId(), request);

        if (result != EntryResultCode.SUCCESS && result != EntryResultCode.DUPLICATE_REPLAY) {
            throw new BusinessException(EntryErrorCode.from(result));
        }
        return new EntryOutcome(result, EntryResponse.accepted(request.requestId(), eventId));
    }
}
