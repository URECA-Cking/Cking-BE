package kr.co.cking.event.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.event.application.dto.CreateEventCommand;
import kr.co.cking.event.application.dto.UpdateEventCommand;
import kr.co.cking.event.domain.DrawMethod;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventApprovalRequest;
import kr.co.cking.event.domain.EventErrorCode;
import kr.co.cking.event.application.service.EventCommandService;
import kr.co.cking.event.repository.EventApprovalRequestRepository;
import kr.co.cking.event.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Creator가 소유한 Event의 생성과 운영 명령을 처리한다. */
@Service
@RequiredArgsConstructor
@Transactional
public class CreatorEventService {

    private final CreatorRepository creatorRepository;
    private final EventRepository eventRepository;
    private final EventApprovalRequestRepository approvalRequestRepository;
    private final EventCommandService eventCommandService;

    /** 요청 식별자 기준으로 Event를 멱등 생성하거나 동일 요청의 기존 Event를 반환한다. */
    public Event create(CreateEventCommand command) {
        validateCreate(command);
        return eventRepository.findByRequestId(command.requestId())
                .map(existing -> returnExistingOrThrow(existing, command))
                .orElseGet(() -> saveNewEvent(command));
    }

    /** 요청 Creator가 소유한 삭제되지 않은 Event 목록을 페이지로 조회한다. */
    @Transactional(readOnly = true)
    public Page<Event> findMine(Long userId, Pageable pageable) {
        Creator creator = creatorRepository.findByMemberId(userId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.FORBIDDEN));
        return eventRepository.findByCreatorIdAndDeletedAtIsNullOrderByCreatedAtDescEventIdDesc(creator.getCreatorId(), pageable);
    }

    /** Creator 소유 Event의 새 승인 요청 차수를 만들고 승인 대기 상태로 전이한다. */
    public EventApprovalRequest requestApproval(Long userId, Long eventId) {
        Event event = findOwnedEvent(userId, eventId);
        int nextRound = approvalRequestRepository.findTopByEventIdOrderByApprovalRoundDesc(eventId)
                .map(request -> request.getApprovalRound() + 1)
                .orElse(1);
        EventApprovalRequest approvalRequest = approvalRequestRepository.save(
                new EventApprovalRequest(event.getEventId(), nextRound, userId));
        eventCommandService.requestApproval(eventId);
        return approvalRequest;
    }

    /** Creator 소유의 초안 Event를 논리 삭제한다. */
    public void delete(Long userId, Long eventId) {
        findOwnedEvent(userId, eventId).delete();
    }

    /** Creator 소유 Event를 수정하고 거절 상태면 공통 상태 명령으로 초안에 복귀시킨다. */
    public Event update(UpdateEventCommand command) {
        validateUpdate(command);
        Event event = findOwnedEvent(command.userId(), command.eventId());
        if (event.getStatus() == kr.co.cking.event.domain.EventStatus.REJECTED) {
            eventCommandService.changeToDraft(command.eventId());
        }
        event.update(command.title().trim(), command.description(), command.startAt(), command.endAt(),
                command.winnerCount(), command.drawMethod());
        return event;
    }

    /** 기존 Event가 동일 본문이면 재사용하고 다르면 멱등성 충돌을 발생시킨다. */
    private Event returnExistingOrThrow(Event existing, CreateEventCommand command) {
        if (!sameBody(existing, command)) {
            throw new BusinessException(EventErrorCode.IDEMPOTENCY_CONFLICT);
        }
        return existing;
    }

    /** Creator 소유자를 확인한 뒤 DRAFT Event를 저장한다. */
    private Event saveNewEvent(CreateEventCommand command) {
        Creator creator = creatorRepository.findByMemberId(command.userId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.FORBIDDEN));
        return eventRepository.save(new Event(
                creator.getCreatorId(), command.title().trim(), command.description(), command.startAt(), command.endAt(),
                command.winnerCount(), command.drawMethod(), command.userId(), command.requestId()
        ));
    }

    /** 요청한 사용자의 Creator가 소유한 Event를 잠금 상태로 조회한다. */
    private Event findOwnedEvent(Long userId, Long eventId) {
        Creator creator = creatorRepository.findByMemberId(userId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.FORBIDDEN));
        Event event = eventRepository.findByEventId(eventId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (!creator.getCreatorId().equals(event.getCreatorId()) || event.getDeletedAt() != null) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return event;
    }

    /** Event 생성 요청의 필수값과 업무 제약을 검증한다. */
    private void validateCreate(CreateEventCommand command) {
        if (command.userId() == null || command.requestId() == null || command.requestId().isBlank()
                || command.title() == null || command.title().isBlank() || command.startAt() == null
                || command.endAt() == null || command.winnerCount() < 1 || command.drawMethod() != DrawMethod.WEIGHTED
                || !command.startAt().isBefore(command.endAt())) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    /** Event 수정 요청의 필수값과 업무 제약을 검증한다. */
    private void validateUpdate(UpdateEventCommand command) {
        if (command.userId() == null || command.eventId() == null || command.title() == null || command.title().isBlank()
                || command.startAt() == null || command.endAt() == null || command.winnerCount() < 1
                || command.drawMethod() != DrawMethod.WEIGHTED || !command.startAt().isBefore(command.endAt())) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
    }

    /** 저장된 Event와 재시도 요청의 의미 있는 생성 본문이 같은지 비교한다. */
    private boolean sameBody(Event event, CreateEventCommand command) {
        return event.getCreatedBy().equals(command.userId())
                && event.getTitle().equals(command.title().trim())
                && java.util.Objects.equals(event.getDescription(), command.description())
                && event.getStartAt().equals(command.startAt())
                && event.getEndAt().equals(command.endAt())
                && event.getWinnerCount() == command.winnerCount()
                && event.getDrawMethod() == command.drawMethod();
    }
}
