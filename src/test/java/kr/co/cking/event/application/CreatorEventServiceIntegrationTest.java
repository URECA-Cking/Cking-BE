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
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class CreatorEventServiceIntegrationTest {

    @Autowired private CreatorEventService creatorEventService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private CreatorRepository creatorRepository;
    @Autowired private EventReviewService eventReviewService;

    /** 동일 요청 식별자와 동일 본문은 새 Event 대신 기존 Event를 반환하는지 검증한다. */
    @Test
    void sameRequestIdAndBodyReturnsExistingEvent() {
        Member member = memberRepository.save(new Member("크리에이터", null, null, MemberRole.USER));
        creatorRepository.save(new Creator(member.getMemberId(), member.getName()));
        CreateEventCommand command = new CreateEventCommand(
                member.getMemberId(), "550e8400-e29b-41d4-a716-446655440000", "팬미팅", "설명",
                LocalDateTime.now(ZoneOffset.UTC).plusDays(1), LocalDateTime.now(ZoneOffset.UTC).plusDays(2),
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
        Member member = memberRepository.save(new Member("크리에이터", null, null, MemberRole.USER));
        creatorRepository.save(new Creator(member.getMemberId(), member.getName()));
        Event event = creatorEventService.create(new CreateEventCommand(
                member.getMemberId(), "550e8400-e29b-41d4-a716-446655440001", "팬미팅", null,
                LocalDateTime.now(ZoneOffset.UTC).plusDays(1), LocalDateTime.now(ZoneOffset.UTC).plusDays(2),
                1, DrawMethod.WEIGHTED));

        EventApprovalRequest approvalRequest = creatorEventService.requestApproval(member.getMemberId(), event.getEventId());

        assertThat(approvalRequest.getApprovalRound()).isEqualTo(1);
        assertThat(approvalRequest.getStatus()).isEqualTo(EventApprovalRequestStatus.PENDING);
        assertThat(event.getStatus()).isEqualTo(EventStatus.PENDING_APPROVAL);
    }

    /** 관리자 거절이 승인 요청 이력의 거절 사유와 Event 상태를 함께 변경하는지 검증한다. */
    @Test
    void rejectionStoresReasonOnApprovalHistory() {
        Member creatorMember = memberRepository.save(new Member("크리에이터", null, null, MemberRole.USER));
        creatorRepository.save(new Creator(creatorMember.getMemberId(), creatorMember.getName()));
        Member admin = memberRepository.save(new Member("관리자", null, null, MemberRole.ADMIN));
        Event event = creatorEventService.create(new CreateEventCommand(
                creatorMember.getMemberId(), "550e8400-e29b-41d4-a716-446655440002", "팬미팅", null,
                LocalDateTime.now(ZoneOffset.UTC).plusDays(1), LocalDateTime.now(ZoneOffset.UTC).plusDays(2),
                1, DrawMethod.WEIGHTED));
        EventApprovalRequest request = creatorEventService.requestApproval(creatorMember.getMemberId(), event.getEventId());

        eventReviewService.reject(admin.getMemberId(), event.getEventId(), "일정 확인이 필요합니다.");

        assertThat(request.getStatus()).isEqualTo(EventApprovalRequestStatus.REJECTED);
        assertThat(request.getRejectReason()).isEqualTo("일정 확인이 필요합니다.");
        assertThat(event.getStatus()).isEqualTo(EventStatus.REJECTED);
    }

    /** Creator가 자신의 초안 Event를 논리 삭제하면 이후 조회 대상에서 제외할 수 있는지 검증한다. */
    @Test
    void creatorSoftDeletesOwnDraftEvent() {
        Member member = memberRepository.save(new Member("크리에이터", null, null, MemberRole.USER));
        creatorRepository.save(new Creator(member.getMemberId(), member.getName()));
        Event event = creatorEventService.create(new CreateEventCommand(
                member.getMemberId(), "550e8400-e29b-41d4-a716-446655440003", "팬미팅", null,
                LocalDateTime.now(ZoneOffset.UTC).plusDays(1), LocalDateTime.now(ZoneOffset.UTC).plusDays(2),
                1, DrawMethod.WEIGHTED));

        creatorEventService.delete(member.getMemberId(), event.getEventId());

        assertThat(event.getDeletedAt()).isNotNull();
    }

    /** 거절된 Event를 Creator가 수정하면 상태가 EventCommandService를 통해 DRAFT로 복귀하는지 검증한다. */
    @Test
    void updatingRejectedEventReturnsItToDraft() {
        Member creatorMember = memberRepository.save(new Member("크리에이터", null, null, MemberRole.USER));
        creatorRepository.save(new Creator(creatorMember.getMemberId(), creatorMember.getName()));
        Member admin = memberRepository.save(new Member("관리자", null, null, MemberRole.ADMIN));
        Event event = creatorEventService.create(new CreateEventCommand(
                creatorMember.getMemberId(), "550e8400-e29b-41d4-a716-446655440004", "기존", null,
                LocalDateTime.now(ZoneOffset.UTC).plusDays(1), LocalDateTime.now(ZoneOffset.UTC).plusDays(2),
                1, DrawMethod.WEIGHTED));
        creatorEventService.requestApproval(creatorMember.getMemberId(), event.getEventId());
        eventReviewService.reject(admin.getMemberId(), event.getEventId(), "수정 필요");

        creatorEventService.update(new UpdateEventCommand(
                creatorMember.getMemberId(), event.getEventId(), "변경", "변경 설명",
                LocalDateTime.now(ZoneOffset.UTC).plusDays(3), LocalDateTime.now(ZoneOffset.UTC).plusDays(4),
                2, DrawMethod.WEIGHTED));

        assertThat(event.getStatus()).isEqualTo(EventStatus.DRAFT);
        assertThat(event.getTitle()).isEqualTo("변경");
    }

    /** 종료된 Event는 관리자 승인을 허용하지 않는지 검증한다. */
    @Test
    void expiredEventCannotBeApproved() {
        Member creatorMember = memberRepository.save(new Member("크리에이터", null, null, MemberRole.USER));
        creatorRepository.save(new Creator(creatorMember.getMemberId(), creatorMember.getName()));
        Member admin = memberRepository.save(new Member("관리자", null, null, MemberRole.ADMIN));
        Event event = creatorEventService.create(new CreateEventCommand(creatorMember.getMemberId(),
                "550e8400-e29b-41d4-a716-446655440005", "종료 이벤트", null,
                LocalDateTime.now(ZoneOffset.UTC).minusDays(2), LocalDateTime.now(ZoneOffset.UTC).minusDays(1),
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
        Member member = memberRepository.save(new Member("크리에이터", null, null, MemberRole.USER));
        creatorRepository.save(new Creator(member.getMemberId(), member.getName()));
        Event event = creatorEventService.create(new CreateEventCommand(member.getMemberId(),
                "550e8400-e29b-41d4-a716-446655440006", "목록 이벤트", null,
                LocalDateTime.now(ZoneOffset.UTC).plusDays(1), LocalDateTime.now(ZoneOffset.UTC).plusDays(2), 1, DrawMethod.WEIGHTED));
        creatorEventService.delete(member.getMemberId(), event.getEventId());

        assertThat(creatorEventService.findMine(member.getMemberId(), PageRequest.of(0, 20)).getTotalElements()).isZero();
    }
}
