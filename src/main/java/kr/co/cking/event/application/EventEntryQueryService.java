package kr.co.cking.event.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.event.application.dto.EntryHistoryItemResponse;
import kr.co.cking.event.application.dto.EntryHistoryPage;
import kr.co.cking.event.repository.EventEntryRepository;
import kr.co.cking.event.repository.EventEntryView;
import kr.co.cking.event.repository.EventRepository;
import kr.co.cking.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventEntryQueryService {

    private final EventEntryRepository entryRepository;
    private final EventRepository eventRepository;
    private final MemberRepository memberRepository;

    public EntryHistoryPage getMyEntries(Long eventId, Long memberId, int size, String cursor) {
        validateResources(eventId, memberId);

        List<EventEntryView> entries;
        if (cursor == null || cursor.isBlank()) {
            entries = entryRepository.findFirstPageView(memberId, eventId, PageRequest.of(0, size + 1));
        } else {
            Cursor decoded = Cursor.decode(cursor);
            entries = entryRepository.findAfterCursorView(
                    memberId, eventId, decoded.appliedAt(), decoded.entryId(), PageRequest.of(0, size + 1));
        }

        boolean hasNext = entries.size() > size;
        List<EventEntryView> page = hasNext ? entries.subList(0, size) : entries;
        String nextCursor = hasNext ? Cursor.encode(page.get(page.size() - 1)) : null;
        return new EntryHistoryPage(page.stream().map(EntryHistoryItemResponse::from).toList(), nextCursor, hasNext);
    }

    private void validateResources(Long eventId, Long memberId) {
        if (!memberRepository.existsById(memberId)
                || !eventRepository.existsByEventIdAndDeletedAtIsNull(eventId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    private record Cursor(Instant appliedAt, Long entryId) {
        private static String encode(EventEntryView entry) {
            String raw = entry.getAppliedAt().toString() + "|" + entry.getEntryId();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        private static Cursor decode(String value) {
            try {
                String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
                String[] parts = raw.split("\\|", -1);
                if (parts.length != 2) {
                    throw new IllegalArgumentException();
                }
                return new Cursor(Instant.parse(parts[0]), Long.parseLong(parts[1]));
            } catch (RuntimeException e) {
                throw new BusinessException(CommonErrorCode.VALIDATION_FAILED, "cursor: 올바르지 않은 커서입니다.");
            }
        }
    }
}
