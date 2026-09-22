package kr.co.cking.event.presentation.dto;

import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventStatus;
import org.springframework.data.domain.Page;
import java.util.List;
import kr.co.cking.event.domain.EventApprovalRequest;
import kr.co.cking.event.domain.EventApprovalRequestStatus;

/** Creator Event 관리 API의 응답 데이터를 정의한다. */
public final class EventManagementResponse {

    private EventManagementResponse() {
    }

    /** Event 명령 결과의 식별자와 상태를 반환한다. */
    public record Result(Long eventId, EventStatus status) {
        /** Event 엔티티를 간결한 명령 결과로 변환한다. */
        public static Result from(Event event) {
            return new Result(event.getEventId(), event.getStatus());
        }
    }

    /** Creator Event 목록의 한 항목을 반환한다. */
    public record Item(Long eventId, String title, java.time.Instant startAt, java.time.Instant endAt, int winnerCount,
                       kr.co.cking.event.domain.DrawMethod drawMethod, String prizeAlgorithmVersion,
                       EventStatus status, java.time.Instant createdAt,
                       List<kr.co.cking.event.application.dto.PrizeResult> prizes) {
        /** Event 엔티티를 목록 항목으로 변환한다. */
        public static Item from(Event event) {
            return new Item(event.getEventId(), event.getTitle(), event.getStartAt(),
                    event.getEndAt(), event.getWinnerCount(), kr.co.cking.event.domain.DrawMethod.valueOf(event.getDrawMethod()),
                    event.getPrizeAlgorithmVersion(), event.getStatus(), event.getCreatedAt(), event.getPrizeConfigs().stream()
                            .map(kr.co.cking.event.application.dto.PrizeResult::from).toList());
        }
    }

    /** 관리자 승인 대기 목록의 승인 요청 항목을 반환한다. */
    public record ApprovalItem(Long eventId, Long creatorId, String creatorName, String title, java.time.Instant startAt,
                               java.time.Instant endAt, int winnerCount, kr.co.cking.event.domain.DrawMethod drawMethod,
                               String prizeAlgorithmVersion, EventStatus status, int approvalRound,
                               java.time.Instant requestedAt,
                               List<kr.co.cking.event.application.dto.PrizeResult> prizes) {
        /** 승인 요청 엔티티를 관리자 목록 항목으로 변환한다. */
        public static ApprovalItem from(kr.co.cking.event.application.EventReviewService.PendingEvent pending) {
            Event event = pending.event(); EventApprovalRequest request = pending.request();
            return new ApprovalItem(event.getEventId(), event.getCreatorId(), pending.creatorName(), event.getTitle(),
                    event.getStartAt(), event.getEndAt(),
                    event.getWinnerCount(), kr.co.cking.event.domain.DrawMethod.valueOf(event.getDrawMethod()),
                    event.getPrizeAlgorithmVersion(), event.getStatus(), request.getApprovalRound(),
                    request.getRequestedAt().toInstant(java.time.ZoneOffset.UTC), event.getPrizeConfigs().stream()
                            .map(kr.co.cking.event.application.dto.PrizeResult::from).toList());
        }
    }

    /** 공통 목록 페이지 메타데이터를 반환한다. */
    public record PageResult<T>(List<T> items, int page, int size, long totalElements, int totalPages, boolean hasNext) {
        /** Spring Page를 API 페이지 응답으로 변환한다. */
        public static <T> PageResult<T> from(Page<?> source, List<T> items) {
            return new PageResult<>(items, source.getNumber(), source.getSize(), source.getTotalElements(), source.getTotalPages(), source.hasNext());
        }
    }
}
