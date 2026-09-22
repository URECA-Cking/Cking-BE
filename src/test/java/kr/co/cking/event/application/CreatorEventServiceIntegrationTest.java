package kr.co.cking.event.application;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.event.application.dto.CreateEventCommand;
import kr.co.cking.event.application.dto.UpdateEventCommand;
import kr.co.cking.event.domain.DrawMethod;
import kr.co.cking.event.domain.Event;
import kr.co.cking.event.domain.EventApprovalRequest;
import kr.co.cking.event.domain.EventApprovalRequestStatus;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.drawing.domain.prize.PrizeAllocationAlgorithmVersion;
import kr.co.cking.event.repository.EventRepository;
import kr.co.cking.event.repository.EventApprovalRequestRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CreatorEventServiceIntegrationTest {

    @Autowired private CreatorEventService creatorEventService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private CreatorRepository creatorRepository;
    @Autowired private EventReviewService eventReviewService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventApprovalRequestRepository approvalRequestRepository;

    private final List<Long> memberIds = new ArrayList<>();
    private final List<Long> creatorIds = new ArrayList<>();

    /** 고정 멱등 키를 사용하는 테스트가 이전 실행 데이터와 충돌하지 않도록 Event만 정리한다. */
    @BeforeEach
    void cleanTestEvents() {
        jdbcTemplate.update("DELETE FROM event_approval_request WHERE event_id IN (SELECT event_id FROM event WHERE request_id LIKE ?)", "550e8400-e29b-41d4-a716-4466554400%");
        jdbcTemplate.update("DELETE FROM event WHERE request_id LIKE ?", "550e8400-e29b-41d4-a716-4466554400%");
    }

    /** 테스트가 생성한 Event와 연관 fixture를 외래 키 의존성의 역순으로 정리한다. */
    @AfterEach
    void cleanUp() {
        cleanTestEvents();
        creatorRepository.deleteAllById(creatorIds);
        memberRepository.deleteAllById(memberIds);
    }

    @Test
    void 후보와_상품_알고리즘_선택을_Event에_독립적으로_저장한다() {
        Member member = saveMember(new Member("선택 테스트", null, null, MemberRole.USER));
        saveCreator(new Creator(member.getMemberId(), member.getName()));

        Event event = creatorEventService.create(new CreateEventCommand(
                member.getMemberId(), "550e8400-e29b-41d4-a716-446655440099", "알고리즘 조합", null,
                Instant.now().plus(java.time.Duration.ofDays(1)),
                Instant.now().plus(java.time.Duration.ofDays(2)), 1, DrawMethod.UNIFORM,
                PrizeAllocationAlgorithmVersion.PRIZE_UNIFORM_V1, List.of()));

        Event persisted = eventRepository.findById(event.getEventId()).orElseThrow();
        assertThat(persisted.getDrawMethod()).isEqualTo("UNIFORM");
        assertThat(persisted.getPrizeAlgorithmVersion()).isEqualTo("PRIZE_UNIFORM_V1");
    }

    /** 동일 요청 식별자와 동일 본문은 새 Event 대신 기존 Event를 반환하는지 검증한다. */
    @Test
    void sameRequestIdAndBodyReturnsExistingEvent() {
        Member member = saveMember(new Member("크리에이터", null, null, MemberRole.USER));
        saveCreator(new Creator(member.getMemberId(), member.getName()));
        CreateEventCommand command = new CreateEventCommand(
                member.getMemberId(), "550e8400-e29b-41d4-a716-446655440000", "팬미팅", "설명",
                Instant.parse("2030-01-02T03:04:05.123456789Z"),
                Instant.parse("2030-01-03T03:04:05.123456789Z"),
                3, DrawMethod.WEIGHTED
        );

        Event first = creatorEventService.create(command);
        Event retried = creatorEventService.create(command);

        assertThat(retried.getEventId()).isEqualTo(first.getEventId());
        assertThat(retried.getStatus()).isEqualTo(first.getStatus());
    }

    /** Creator의 승인 요청이 첫 차수 이력을 만들고 Event를 승인 대기로 전이하는지 검증한다. */
    @Test
    void approvalRequestCreatesFirstRoundAndMovesEventToPending() {
        Member member = saveMember(new Member("크리에이터", null, null, MemberRole.USER));
        saveCreator(new Creator(member.getMemberId(), member.getName()));
        Event event = creatorEventService.create(new CreateEventCommand(
                member.getMemberId(), "550e8400-e29b-41d4-a716-446655440001", "팬미팅", null,
                Instant.now().plus(java.time.Duration.ofDays(1)), Instant.now().plus(java.time.Duration.ofDays(2)),
                1, DrawMethod.WEIGHTED));

        EventApprovalRequest approvalRequest = creatorEventService.requestApproval(member.getMemberId(), event.getEventId());

        assertThat(approvalRequest.getApprovalRound()).isEqualTo(1);
        assertThat(approvalRequest.getStatus()).isEqualTo(EventApprovalRequestStatus.PENDING);
        assertThat(eventRepository.findById(event.getEventId()).orElseThrow().getStatus()).isEqualTo(EventStatus.PENDING_APPROVAL);
    }

    /** 관리자 거절이 승인 요청 이력의 거절 사유와 Event 상태를 함께 변경하는지 검증한다. */
    @Test
    void rejectionStoresReasonOnApprovalHistory() {
        Member creatorMember = saveMember(new Member("크리에이터", null, null, MemberRole.USER));
        saveCreator(new Creator(creatorMember.getMemberId(), creatorMember.getName()));
        Member admin = saveMember(new Member("관리자", null, null, MemberRole.ADMIN));
        Event event = creatorEventService.create(new CreateEventCommand(
                creatorMember.getMemberId(), "550e8400-e29b-41d4-a716-446655440002", "팬미팅", null,
                Instant.now().plus(java.time.Duration.ofDays(1)), Instant.now().plus(java.time.Duration.ofDays(2)),
                1, DrawMethod.WEIGHTED));
        EventApprovalRequest request = creatorEventService.requestApproval(creatorMember.getMemberId(), event.getEventId());

        eventReviewService.reject(admin.getMemberId(), event.getEventId(), "일정 확인이 필요합니다.");

        EventApprovalRequest reviewed = approvalRequestRepository.findById(request.getId()).orElseThrow();
        assertThat(reviewed.getStatus()).isEqualTo(EventApprovalRequestStatus.REJECTED);
        assertThat(reviewed.getRejectReason()).isEqualTo("일정 확인이 필요합니다.");
        assertThat(eventRepository.findById(event.getEventId()).orElseThrow().getStatus()).isEqualTo(EventStatus.REJECTED);
    }

    /** Creator가 자신의 초안 Event를 논리 삭제하면 이후 조회 대상에서 제외할 수 있는지 검증한다. */
    @Test
    void creatorSoftDeletesOwnDraftEvent() {
        Member member = saveMember(new Member("크리에이터", null, null, MemberRole.USER));
        saveCreator(new Creator(member.getMemberId(), member.getName()));
        Event event = creatorEventService.create(new CreateEventCommand(
                member.getMemberId(), "550e8400-e29b-41d4-a716-446655440003", "팬미팅", null,
                Instant.now().plus(java.time.Duration.ofDays(1)), Instant.now().plus(java.time.Duration.ofDays(2)),
                1, DrawMethod.WEIGHTED));

        creatorEventService.delete(member.getMemberId(), event.getEventId());

        assertThat(eventRepository.findById(event.getEventId()).orElseThrow().getDeletedAt()).isNotNull();
    }

    /** Creator가 거절된 Event도 논리 삭제할 수 있는지 검증한다. */
    @Test
    void creatorSoftDeletesOwnRejectedEvent() {
        Member creatorMember = saveMember(new Member("크리에이터", null, null, MemberRole.USER));
        saveCreator(new Creator(creatorMember.getMemberId(), creatorMember.getName()));
        Member admin = saveMember(new Member("관리자", null, null, MemberRole.ADMIN));
        Event event = creatorEventService.create(new CreateEventCommand(
                creatorMember.getMemberId(), "550e8400-e29b-41d4-a716-446655440014", "팬미팅", null,
                Instant.now().plus(java.time.Duration.ofDays(1)), Instant.now().plus(java.time.Duration.ofDays(2)),
                1, DrawMethod.WEIGHTED));
        creatorEventService.requestApproval(creatorMember.getMemberId(), event.getEventId());
        eventReviewService.reject(admin.getMemberId(), event.getEventId(), "수정 필요");

        creatorEventService.delete(creatorMember.getMemberId(), event.getEventId());

        assertThat(eventRepository.findById(event.getEventId()).orElseThrow().getDeletedAt()).isNotNull();
    }

    /** 거절된 Event를 Creator가 수정하면 상태가 EventCommandService를 통해 DRAFT로 복귀하는지 검증한다. */
    @Test
    void updatingRejectedEventReturnsItToDraft() {
        Member creatorMember = saveMember(new Member("크리에이터", null, null, MemberRole.USER));
        saveCreator(new Creator(creatorMember.getMemberId(), creatorMember.getName()));
        Member admin = saveMember(new Member("관리자", null, null, MemberRole.ADMIN));
        Event event = creatorEventService.create(new CreateEventCommand(
                creatorMember.getMemberId(), "550e8400-e29b-41d4-a716-446655440004", "기존", null,
                Instant.now().plus(java.time.Duration.ofDays(1)), Instant.now().plus(java.time.Duration.ofDays(2)),
                1, DrawMethod.WEIGHTED));
        creatorEventService.requestApproval(creatorMember.getMemberId(), event.getEventId());
        eventReviewService.reject(admin.getMemberId(), event.getEventId(), "수정 필요");

        creatorEventService.update(new UpdateEventCommand(
                creatorMember.getMemberId(), event.getEventId(), "변경", "변경 설명",
                Instant.now().plus(java.time.Duration.ofDays(3)), Instant.now().plus(java.time.Duration.ofDays(4)),
                2, DrawMethod.WEIGHTED));

        Event updated = eventRepository.findById(event.getEventId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(EventStatus.DRAFT);
        assertThat(updated.getTitle()).isEqualTo("변경");
    }

    /** 종료된 Event는 관리자 승인을 허용하지 않는지 검증한다. */
    @Test
    void expiredEventCannotBeApproved() {
        Member creatorMember = saveMember(new Member("크리에이터", null, null, MemberRole.USER));
        saveCreator(new Creator(creatorMember.getMemberId(), creatorMember.getName()));
        Member admin = saveMember(new Member("관리자", null, null, MemberRole.ADMIN));
        Event event = creatorEventService.create(new CreateEventCommand(creatorMember.getMemberId(),
                "550e8400-e29b-41d4-a716-446655440005", "종료 이벤트", null,
                Instant.now().minus(java.time.Duration.ofDays(2)), Instant.now().minus(java.time.Duration.ofDays(1)),
                1, DrawMethod.WEIGHTED));
        creatorEventService.requestApproval(creatorMember.getMemberId(), event.getEventId());

        assertThatThrownBy(() -> eventReviewService.approve(admin.getMemberId(), event.getEventId()))
                .isInstanceOf(kr.co.cking.common.exception.BusinessException.class)
                .extracting(e -> ((kr.co.cking.common.exception.BusinessException) e).getErrorCode())
                .isEqualTo(kr.co.cking.event.domain.EventErrorCode.INVALID_STATE);
    }

    /** Creator 목록이 논리 삭제된 Event를 제외하는지 검증한다. */
    @Test
    void creatorEventListExcludesSoftDeletedEvents() {
        Member member = saveMember(new Member("크리에이터", null, null, MemberRole.USER));
        saveCreator(new Creator(member.getMemberId(), member.getName()));
        Event event = creatorEventService.create(new CreateEventCommand(member.getMemberId(),
                "550e8400-e29b-41d4-a716-446655440006", "목록 이벤트", null,
                Instant.now().plus(java.time.Duration.ofDays(1)), Instant.now().plus(java.time.Duration.ofDays(2)), 1, DrawMethod.WEIGHTED));
        creatorEventService.delete(member.getMemberId(), event.getEventId());

        assertThat(creatorEventService.findMine(member.getMemberId(), PageRequest.of(0, 20)).getTotalElements()).isZero();
    }

    /** 존재하지 않는 Member의 Event 생성 요청은 리소스 없음으로 거부한다. */
    @Test
    void missingMemberCannotCreateEvent() {
        assertThatThrownBy(() -> creatorEventService.create(new CreateEventCommand(
                9_999_999L, "550e8400-e29b-41d4-a716-446655440015", "팬미팅", null,
                Instant.now().plus(java.time.Duration.ofDays(1)), Instant.now().plus(java.time.Duration.ofDays(2)),
                1, DrawMethod.WEIGHTED)))
                .isInstanceOf(kr.co.cking.common.exception.BusinessException.class)
                .extracting(e -> ((kr.co.cking.common.exception.BusinessException) e).getErrorCode())
                .isEqualTo(kr.co.cking.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    /** 존재하지 않는 Member의 Event 목록 요청은 리소스 없음으로 거부한다. */
    @Test
    void missingMemberCannotFindCreatorEvents() {
        assertThatThrownBy(() -> creatorEventService.findMine(9_999_999L, PageRequest.of(0, 20)))
                .isInstanceOf(kr.co.cking.common.exception.BusinessException.class)
                .extracting(e -> ((kr.co.cking.common.exception.BusinessException) e).getErrorCode())
                .isEqualTo(kr.co.cking.common.exception.CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    /** 존재하지만 Creator가 아닌 Member의 Event 목록 요청은 권한 없음으로 거부한다. */
    @Test
    void nonCreatorCannotFindCreatorEvents() {
        Member member = saveMember(new Member("일반 사용자", null, null, MemberRole.USER));

        assertThatThrownBy(() -> creatorEventService.findMine(member.getMemberId(), PageRequest.of(0, 20)))
                .isInstanceOf(kr.co.cking.common.exception.BusinessException.class)
                .extracting(e -> ((kr.co.cking.common.exception.BusinessException) e).getErrorCode())
                .isEqualTo(kr.co.cking.common.exception.CommonErrorCode.FORBIDDEN);
    }

    /** 동일 요청 식별자에 다른 생성 본문을 재시도하면 멱등성 충돌을 반환하는지 검증한다. */
    @Test
    void sameRequestIdWithDifferentBodyThrowsIdempotencyConflict() {
        Member member = saveMember(new Member("크리에이터", null, null, MemberRole.USER));
        saveCreator(new Creator(member.getMemberId(), member.getName()));
        creatorEventService.create(new CreateEventCommand(member.getMemberId(),
                "550e8400-e29b-41d4-a716-446655440007", "원본", null,
                Instant.now().plus(java.time.Duration.ofDays(1)), Instant.now().plus(java.time.Duration.ofDays(2)), 1, DrawMethod.WEIGHTED));

        assertThatThrownBy(() -> creatorEventService.create(new CreateEventCommand(member.getMemberId(),
                "550e8400-e29b-41d4-a716-446655440007", "다른 제목", null,
                Instant.now().plus(java.time.Duration.ofDays(1)), Instant.now().plus(java.time.Duration.ofDays(2)), 1, DrawMethod.WEIGHTED)))
                .isInstanceOf(kr.co.cking.common.exception.BusinessException.class)
                .extracting(e -> ((kr.co.cking.common.exception.BusinessException) e).getErrorCode())
                .isEqualTo(kr.co.cking.event.domain.EventErrorCode.IDEMPOTENCY_CONFLICT);
    }

    /** Creator가 아닌 일반 사용자는 Event를 생성할 수 없는지 검증한다. */
    @Test
    void nonCreatorCannotCreateEvent() {
        Member member = saveMember(new Member("일반 사용자", null, null, MemberRole.USER));

        assertThatThrownBy(() -> creatorEventService.create(new CreateEventCommand(
                member.getMemberId(), "550e8400-e29b-41d4-a716-446655440009", "팬미팅", null,
                Instant.now().plus(java.time.Duration.ofDays(1)), Instant.now().plus(java.time.Duration.ofDays(2)),
                1, DrawMethod.WEIGHTED)))
                .isInstanceOf(kr.co.cking.common.exception.BusinessException.class)
                .extracting(e -> ((kr.co.cking.common.exception.BusinessException) e).getErrorCode())
                .isEqualTo(kr.co.cking.common.exception.CommonErrorCode.FORBIDDEN);
    }

    /** 다른 Creator가 소유한 Event의 내용을 변경할 수 없는지 검증한다. */
    @Test
    void creatorCannotUpdateAnotherCreatorsEvent() {
        Member owner = saveMember(new Member("소유 크리에이터", null, null, MemberRole.USER));
        saveCreator(new Creator(owner.getMemberId(), owner.getName()));
        Member other = saveMember(new Member("다른 크리에이터", null, null, MemberRole.USER));
        saveCreator(new Creator(other.getMemberId(), other.getName()));
        Event event = creatorEventService.create(new CreateEventCommand(
                owner.getMemberId(), "550e8400-e29b-41d4-a716-446655440010", "원본", null,
                Instant.now().plus(java.time.Duration.ofDays(1)), Instant.now().plus(java.time.Duration.ofDays(2)),
                1, DrawMethod.WEIGHTED));

        assertThatThrownBy(() -> creatorEventService.update(new UpdateEventCommand(
                other.getMemberId(), event.getEventId(), "변경", null,
                Instant.now().plus(java.time.Duration.ofDays(3)), Instant.now().plus(java.time.Duration.ofDays(4)),
                1, DrawMethod.WEIGHTED)))
                .isInstanceOf(kr.co.cking.common.exception.BusinessException.class)
                .extracting(e -> ((kr.co.cking.common.exception.BusinessException) e).getErrorCode())
                .isEqualTo(kr.co.cking.common.exception.CommonErrorCode.FORBIDDEN);
    }

    /** 빈 제목 또는 역전된 시간 범위의 생성 명령을 거부하는지 검증한다. */
    @Test
    void invalidCreateInputIsRejected() {
        Member member = saveMember(new Member("크리에이터", null, null, MemberRole.USER));
        saveCreator(new Creator(member.getMemberId(), member.getName()));
        Instant startAt = Instant.now().plus(java.time.Duration.ofDays(2));

        assertThatThrownBy(() -> creatorEventService.create(new CreateEventCommand(
                member.getMemberId(), "550e8400-e29b-41d4-a716-446655440011", " ", null,
                startAt, startAt.minus(java.time.Duration.ofDays(1)), 1, DrawMethod.WEIGHTED)))
                .isInstanceOf(kr.co.cking.common.exception.BusinessException.class)
                .extracting(e -> ((kr.co.cking.common.exception.BusinessException) e).getErrorCode())
                .isEqualTo(kr.co.cking.common.exception.CommonErrorCode.VALIDATION_FAILED);
    }

    /** 수정 명령의 필수값이 잘못되면 Event 내용을 변경하지 않고 거부하는지 검증한다. */
    @Test
    void invalidUpdateInputIsRejectedWithoutChangingEvent() {
        Member member = saveMember(new Member("크리에이터", null, null, MemberRole.USER));
        saveCreator(new Creator(member.getMemberId(), member.getName()));
        Event event = creatorEventService.create(new CreateEventCommand(
                member.getMemberId(), "550e8400-e29b-41d4-a716-446655440012", "원본", null,
                Instant.now().plus(java.time.Duration.ofDays(1)), Instant.now().plus(java.time.Duration.ofDays(2)),
                1, DrawMethod.WEIGHTED));

        assertThatThrownBy(() -> creatorEventService.update(new UpdateEventCommand(
                member.getMemberId(), event.getEventId(), "변경", null,
                Instant.now().plus(java.time.Duration.ofDays(3)), Instant.now().plus(java.time.Duration.ofDays(4)),
                0, DrawMethod.WEIGHTED)))
                .isInstanceOf(kr.co.cking.common.exception.BusinessException.class)
                .extracting(e -> ((kr.co.cking.common.exception.BusinessException) e).getErrorCode())
                .isEqualTo(kr.co.cking.common.exception.CommonErrorCode.VALIDATION_FAILED);
        assertThat(eventRepository.findById(event.getEventId()).orElseThrow().getTitle()).isEqualTo("원본");
    }

    /** 승인 대기 중인 Event는 Creator가 수정할 수 없는지 검증한다. */
    @Test
    void pendingApprovalEventCannotBeUpdated() {
        Member member = saveMember(new Member("크리에이터", null, null, MemberRole.USER));
        saveCreator(new Creator(member.getMemberId(), member.getName()));
        Event event = creatorEventService.create(new CreateEventCommand(
                member.getMemberId(), "550e8400-e29b-41d4-a716-446655440013", "원본", null,
                Instant.now().plus(java.time.Duration.ofDays(1)), Instant.now().plus(java.time.Duration.ofDays(2)),
                1, DrawMethod.WEIGHTED));
        creatorEventService.requestApproval(member.getMemberId(), event.getEventId());

        assertThatThrownBy(() -> creatorEventService.update(new UpdateEventCommand(
                member.getMemberId(), event.getEventId(), "변경", null,
                Instant.now().plus(java.time.Duration.ofDays(3)), Instant.now().plus(java.time.Duration.ofDays(4)),
                1, DrawMethod.WEIGHTED)))
                .isInstanceOf(kr.co.cking.common.exception.BusinessException.class)
                .extracting(e -> ((kr.co.cking.common.exception.BusinessException) e).getErrorCode())
                .isEqualTo(kr.co.cking.event.domain.EventErrorCode.INVALID_STATE);
        assertThat(eventRepository.findById(event.getEventId()).orElseThrow().getTitle()).isEqualTo("원본");
    }

    /** HTTP 계층을 거치지 않은 생성 명령도 UUID 형식이 아닌 requestId를 거부하는지 검증한다. */
    @Test
    void createRejectsNonUuidRequestId() {
        Member member = saveMember(new Member("크리에이터", null, null, MemberRole.USER));
        saveCreator(new Creator(member.getMemberId(), member.getName()));

        assertThatThrownBy(() -> creatorEventService.create(new CreateEventCommand(
                member.getMemberId(), "not-a-uuid", "팬미팅", null,
                Instant.now().plus(java.time.Duration.ofDays(1)), Instant.now().plus(java.time.Duration.ofDays(2)),
                1, DrawMethod.WEIGHTED)))
                .isInstanceOf(kr.co.cking.common.exception.BusinessException.class)
                .extracting(e -> ((kr.co.cking.common.exception.BusinessException) e).getErrorCode())
                .isEqualTo(kr.co.cking.common.exception.CommonErrorCode.VALIDATION_FAILED);
    }

    /** Member를 저장하고 사후 정리 대상에 등록한다. */
    private Member saveMember(Member member) {
        Member savedMember = memberRepository.save(member);
        memberIds.add(savedMember.getMemberId());
        return savedMember;
    }

    /** Creator를 저장하고 사후 정리 대상에 등록한다. */
    private Creator saveCreator(Creator creator) {
        Creator savedCreator = creatorRepository.save(creator);
        creatorIds.add(savedCreator.getCreatorId());
        return savedCreator;
    }
}
