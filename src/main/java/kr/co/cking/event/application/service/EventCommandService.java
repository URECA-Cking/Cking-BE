package kr.co.cking.event.application.service;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Consumer;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
@Transactional
public class EventCommandService {

    private final EventRepository eventRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** DRAFT Event를 승인 대기 상태로 전이한다. */
    public void requestApproval(Long eventId) {
        requestApproval(eventId, event -> null);
    }

    /** 잠금 상태의 DRAFT Event에 승인 요청 이력을 기록한 뒤 승인 대기로 전이한다. */
    public <T> T requestApproval(Long eventId, Function<Event, T> beforeTransition) {
        return execute(eventId, event -> {
            T result = beforeTransition.apply(event);
            event.requestApproval();
            return result;
        });
    }

    /** 승인 대기 Event를 예약 상태로 전이한다. */
    public void approve(Long eventId) {
        approve(eventId, event -> null);
    }

    /** 잠금 상태의 승인 대기 Event를 심사 이력 처리 후 예약 상태로 전이한다. */
    public <T> T approve(Long eventId, Function<Event, T> beforeTransition) {
        return execute(eventId, event -> {
            T result = beforeTransition.apply(event);
            event.approve();
            return result;
        });
    }

    /** 예약된 Event를 응모 가능한 공개 상태로 전이한다. */
    public void open(Long eventId) {
        execute(eventId, event -> {
            event.open();
            return null;
        });
        eventPublisher.publishEvent(new EventOpenedEvent(eventId));
    }

    /** 초기 추첨이 완료된 CLOSED Event를 추첨 완료 상태로 전이한다. */
    public void completeDrawing(Long eventId) {
        execute(eventId, event -> {
            event.completeDrawing();
            return null;
        });
    }

    /** 추첨이 완료된 Event를 시스템4 결과 공개 흐름에서 공개 상태로 전이한다. */
    public void publish(Long eventId) {
        execute(eventId, event -> {
            event.publish();
            return null;
        });
    }

    /** 승인 대기 Event를 거절 상태로 전이한다. */
    public void reject(Long eventId, String reason) {
        reject(eventId, event -> null);
    }

    /** 잠금 상태의 승인 대기 Event를 심사 이력 처리 후 거절 상태로 전이한다. */
    public <T> T reject(Long eventId, Function<Event, T> beforeTransition) {
        return execute(eventId, event -> {
            T result = beforeTransition.apply(event);
            event.reject();
            return result;
        });
    }

    /** 거절된 Event를 다시 수정 가능한 초안 상태로 전이한다. */
    public void changeToDraft(Long eventId) {
        execute(eventId, event -> {
            event.changeToDraft();
            return null;
        });
    }

    /** 소유권 검증 후 DRAFT 또는 REJECTED Event를 잠금 상태에서 수정한다. */
    public Event update(Long eventId, Consumer<Event> authorize, Consumer<Event> updater) {
        return execute(eventId, event -> {
            authorize.accept(event);
            if (event.getStatus() == kr.co.cking.event.domain.EventStatus.REJECTED) {
                event.changeToDraft();
            }
            updater.accept(event);
            return event;
        });
    }

    /** 소유권 검증 후 삭제 가능한 Event를 잠금 상태에서 논리 삭제한다. */
    public void delete(Long eventId, Consumer<Event> authorize) {
        execute(eventId, event -> {
            authorize.accept(event);
            event.delete();
            return null;
        });
    }

    /** Event 행 잠금 획득과 명령 실행을 하나의 상태 변경 진입점으로 묶는다. */
    private <T> T execute(Long eventId, Function<Event, T> command) {
        return command.apply(findEvent(eventId));
    }

    /** 상태 변경을 직렬화하도록 Event 행을 잠근 뒤 조회한다. */
    private Event findEvent(Long eventId) {
        return eventRepository.findByEventId(eventId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
    }
}
