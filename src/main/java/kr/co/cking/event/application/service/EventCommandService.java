package kr.co.cking.event.application.service;

import java.time.Clock;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventErrorCode;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.event.repository.EventRepository;
import lombok.RequiredArgsConstructor;

/**
 * Event 상태 전이 전담 서비스. 승인·거절 등은 파트4(Creator·Event 운영, 이슈 #31) 담당,
 * 마감 처리(OPEN→CLOSING→CLOSED)는 파트2(EventLifecycleScheduler, 이슈 #59) 담당이다
 * ([[ticle-event-part2-part4-contract]]).
 *
 * <p>Tx1(OPEN→CLOSING)과 Tx2(CLOSING→CLOSED)는 서로 다른 트랜잭션으로 분리한다 - 그 사이의
 * Drain 대기(awaitDrain)를 짧은 DB 트랜잭션 안에 묶으면 안 되기 때문이다(취합v1.5.4 §6.2).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class EventCommandService {

    private final EventRepository eventRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

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

    /**
     * 초기 추첨이 완료된 CLOSED Event를 추첨 완료 상태로 전이한다(FR-P2-025).
     *
     * <p>CLOSED·DRAW_COMPLETED·PUBLISHED는 모두 공개 조회 대상이라 cache:event에 올라갈 수 있다
     * - 수동 마감(endAt 이전 마감)된 Event는 이 전이 시점에도 캐시 엔트리가 살아 있으므로,
     * 마감 전이와 동일하게 무효화 이벤트를 발행한다.
     */
    public void completeDrawing(Long eventId) {
        execute(eventId, event -> {
            event.completeDrawing();
            return null;
        });
        eventPublisher.publishEvent(new EventClosingStateChangedEvent(eventId));
    }

    /** 추첨이 완료된 Event를 시스템4 결과 공개 흐름에서 공개 상태로 전이한다(FR-P2-025 무효화 포함). */
    public void publish(Long eventId) {
        execute(eventId, event -> {
            event.publish(clock.instant());
            return null;
        });
        eventPublisher.publishEvent(new EventClosingStateChangedEvent(eventId));
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
            if (event.getStatus() == EventStatus.REJECTED) {
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
            event.delete(clock.instant());
            return null;
        });
    }

    /**
     * Gate 차단·cutoff 확정(barrier) 이후 호출. 동일 cutoff로 이미 CLOSING이면 재시도로 보고
     * 멱등하게 반환한다. 이미 CLOSED인 경우에도 완료 상태를 반환한다. 그 외 OPEN이 아닌 상태
     * (DRAFT, SCHEDULED 등)에서 호출되면 {@link EventErrorCode#INVALID_STATE}로 구분해 실패시킨다.
     */
    public EventStatus startClosing(Long eventId, String cutoffStreamId) {
        Event event = findEvent(eventId);
        if (event.getStatus() == EventStatus.CLOSING && Objects.equals(event.getCutoffStreamId(), cutoffStreamId)) {
            return EventStatus.CLOSING;
        }
        if (event.getStatus() == EventStatus.CLOSED) {
            return EventStatus.CLOSED;
        }
        event.startClosing(cutoffStreamId);
        eventPublisher.publishEvent(new EventClosingStateChangedEvent(eventId));
        return EventStatus.CLOSING;
    }

    /**
     * Drain 완료 확인 이후 호출. 이미 CLOSED면 재시도로 보고 멱등하게 반환한다. 그 외 CLOSING이
     * 아닌 상태에서 호출되면 {@link EventErrorCode#INVALID_STATE}로 구분해 실패시킨다.
     * closedAt은 서버 시각(clock) 기준으로 이 메서드 내부에서 기록한다.
     */
    public void completeClosing(Long eventId) {
        Event event = findEvent(eventId);
        if (event.getStatus() == EventStatus.CLOSED) {
            return;
        }
        event.completeClosing(clock.instant());
        eventPublisher.publishEvent(new EventClosingStateChangedEvent(eventId));
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
