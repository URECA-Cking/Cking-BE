package kr.co.cking.entry;

import jakarta.validation.Valid;
import kr.co.cking.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class EntryController {

    private final EventEntryService eventEntryService;

    @PostMapping("/api/events/{eventId}/entries")
    public ApiResponse<EntryResponse> apply(@PathVariable Long eventId, @Valid @RequestBody EntryRequest request) {
        EntryOutcome outcome = eventEntryService.apply(eventId, request);
        return ApiResponse.of(outcome.code().name(), outcome.response());
    }
}
