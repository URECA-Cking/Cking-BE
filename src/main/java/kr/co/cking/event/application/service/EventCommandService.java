package kr.co.cking.event.application.service;

import java.time.Instant;
import java.util.function.Consumer;
import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.event.application.EventQueryService;
import kr.co.cking.event.domain.Event;
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
    private final EventQueryService eventQueryService;

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
            event.delete();
            return null;
        });
    }

    /** Gate 차단·cutoff 확정(barrier) 이후 호출. 이미 OPEN이 아니면 멱등하게 그냥 반환한다. */
    public void startClosing(Long eventId, String cutoffStreamId) {
        Event event = findEvent(eventId);
        if (event.getStatus() != EventStatus.OPEN) {
            return;
        }
        event.startClosing(cutoffStreamId);
        eventQueryService.invalidate(eventId);
    }

    /** Drain 완료 확인 이후 호출. 이미 CLOSING이 아니면(이미 CLOSED 등) 멱등하게 그냥 반환한다. */
    public void completeClosing(Long eventId, Instant closedAt) {
        Event event = findEvent(eventId);
        if (event.getStatus() != EventStatus.CLOSING) {
            return;
        }
        event.completeClosing(closedAt);
        eventQueryService.invalidate(eventId);
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
