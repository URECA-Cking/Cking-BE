package kr.co.cking.event.application.dto;

import java.util.List;

public record EntryHistoryPage(List<EntryHistoryItemResponse> items, String nextCursor, boolean hasNext) {
}
