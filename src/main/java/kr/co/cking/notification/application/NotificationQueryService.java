package kr.co.cking.notification.application;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.drawing.domain.Drawing;
import kr.co.cking.drawing.repository.DrawingRepository;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.repository.EventRepository;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.notification.application.dto.NotificationSummary;
import kr.co.cking.notification.domain.Notification;
import kr.co.cking.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationQueryService {

    private static final Sort NOTIFICATION_LIST_SORT = Sort.by(
            Sort.Direction.DESC, "createdAt", "notificationId"
    );

    private final MemberRepository memberRepository;
    private final NotificationRepository notificationRepository;
    private final EventRepository eventRepository;
    private final DrawingRepository drawingRepository;

    /** 요청 Member의 알림만 최신순 페이지로 조회하고 Event·Drawing 정보를 조합한다. */
    public Page<NotificationSummary> findMine(Long memberId, int page, int size) {
        requireMember(memberId);
        Pageable pageable = PageRequest.of(page, size, NOTIFICATION_LIST_SORT);
        Page<Notification> notifications = notificationRepository.findByMemberId(memberId, pageable);
        if (notifications.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, notifications.getTotalElements());
        }
        // 제재 알림처럼 이벤트·추첨이 없는 알림은 ID가 null이므로 조회 대상에서 뺀다.
        List<Long> eventIds = notifications.stream()
                .map(Notification::getEventId).filter(Objects::nonNull).distinct().toList();
        List<Long> drawingIds = notifications.stream()
                .map(Notification::getDrawingId).filter(Objects::nonNull).distinct().toList();
        Map<Long, Event> eventsById = eventIds.isEmpty() ? Map.of() : eventRepository.findByEventIdIn(eventIds)
                .stream().collect(Collectors.toMap(Event::getEventId, Function.identity()));
        Map<Long, Drawing> drawingsById = drawingIds.isEmpty() ? Map.of() : drawingRepository.findAllById(drawingIds)
                .stream().collect(Collectors.toMap(Drawing::getId, Function.identity()));

        return new PageImpl<>(notifications.stream()
                .map(notification -> NotificationSummary.of(
                        notification,
                        relatedOrNull(eventsById, notification.getEventId()),
                        relatedOrNull(drawingsById, notification.getDrawingId())
                ))
                .toList(), pageable, notifications.getTotalElements());
    }

    /** 조회 요청의 주체인 Member가 존재하는지 검증한다. */
    private void requireMember(Long memberId) {
        if (!memberRepository.existsById(memberId)) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "사용자를 찾을 수 없습니다.");
        }
    }

    /** ID가 없으면(제재 알림) null, 있으면 반드시 존재해야 하는 연관 데이터를 꺼낸다. */
    private <T> T relatedOrNull(Map<Long, T> relatedById, Long id) {
        return id == null ? null : requireRelated(relatedById, id);
    }

    /** 외래 키로 보장되는 연관 데이터를 응답 조합에 사용하도록 꺼낸다. */
    private <T> T requireRelated(Map<Long, T> relatedById, Long id) {
        T related = relatedById.get(id);
        if (related == null) {
            throw new IllegalStateException("Notification 연관 데이터가 존재하지 않습니다. id=" + id);
        }
        return related;
    }
}
