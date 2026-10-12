package kr.co.cking.ticket.application;

import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.ticket.application.dto.QuizRewardCommand;
import kr.co.cking.ticket.domain.QuizRewardRequestStatus;
import kr.co.cking.ticket.domain.TicketEarnRequest;
import kr.co.cking.ticket.domain.TicketQuizRewardRequest;
import kr.co.cking.ticket.repository.TicketEarnRequestRepository;
import kr.co.cking.ticket.repository.TicketQuizRewardRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class QuizRewardRequestStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-12T06:00:00Z");
    private static final UUID REQUEST_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    private final TicketQuizRewardRequestRepository quizRepository = mock(TicketQuizRewardRequestRepository.class);
    private final TicketEarnRequestRepository earnRequestRepository = mock(TicketEarnRequestRepository.class);
    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final CreatorRepository creatorRepository = mock(CreatorRepository.class);
    private final QuizRewardRequestStore store = new QuizRewardRequestStore(quizRepository, earnRequestRepository,
            memberRepository, creatorRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    private final QuizRewardCommand command = new QuizRewardCommand(100L, 7L, 10L, 3, REQUEST_ID);

    @BeforeEach
    void setUp() {
        given(memberRepository.existsById(7L)).willReturn(true);
        given(creatorRepository.existsById(10L)).willReturn(true);
        given(quizRepository.saveAndFlush(any(TicketQuizRewardRequest.class))).willAnswer(invocation -> {
            TicketQuizRewardRequest request = invocation.getArgument(0);
            ReflectionTestUtils.setField(request, "quizRewardRequestId", 1L);
            return request;
        });
    }

    @Test
    void 상태_조회는_저장된_요청의_상태만_돌려준다() {
        TicketQuizRewardRequest stored = new TicketQuizRewardRequest(REQUEST_ID.toString(), 100L, 7L, 10L, 3,
                QuizRewardCommand.FINGERPRINT_VERSION, command.computeFingerprint(), NOW);
        ReflectionTestUtils.setField(stored, "status", QuizRewardRequestStatus.UNKNOWN);
        given(quizRepository.findByRewardRequestId(REQUEST_ID.toString())).willReturn(Optional.of(stored));
        given(quizRepository.findByRewardRequestId("missing")).willReturn(Optional.empty());

        assertThat(store.findStatus(REQUEST_ID.toString())).contains(QuizRewardRequestStatus.UNKNOWN);
        assertThat(store.findStatus("missing")).isEmpty();
    }

    @Test
    void 전역_EARN_request를_QUIZ_PENDING으로_선점한_뒤_같은_Transaction에서_Quiz_요청을_RECEIVED로_만든다() {
        QuizRewardAdmission admission = store.insert(command);

        InOrder order = inOrder(memberRepository, creatorRepository, earnRequestRepository, quizRepository);
        order.verify(memberRepository).existsById(7L);
        order.verify(creatorRepository).existsById(10L);
        order.verify(earnRequestRepository).insertPending(REQUEST_ID.toString(), command.computeFingerprint(),
                "QUIZ", NOW);
        order.verify(quizRepository).saveAndFlush(any(TicketQuizRewardRequest.class));
        verify(earnRequestRepository, never()).insertIgnore(anyString(), anyString(), anyString(), any());
        assertThat(admission).isEqualTo(QuizRewardAdmission.received(1L, QuizRewardRequestStatus.RECEIVED));
        ArgumentCaptor<TicketQuizRewardRequest> saved = ArgumentCaptor.forClass(TicketQuizRewardRequest.class);
        verify(quizRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getRewardRequestId()).isEqualTo(REQUEST_ID.toString());
        assertThat(saved.getValue().getAttemptId()).isEqualTo(100L);
        assertThat(saved.getValue().getAmount()).isEqualTo(3L);
        assertThat(saved.getValue().getPayloadFingerprint()).isEqualTo(command.computeFingerprint());
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void 회원이나_Creator가_없으면_아무것도_만들지_않는다() {
        given(memberRepository.existsById(7L)).willReturn(false);
        assertThatThrownBy(() -> store.insert(command)).isInstanceOf(IllegalArgumentException.class);

        given(memberRepository.existsById(7L)).willReturn(true);
        given(creatorRepository.existsById(10L)).willReturn(false);
        assertThatThrownBy(() -> store.insert(command)).isInstanceOf(IllegalArgumentException.class);

        verify(earnRequestRepository, never()).insertPending(anyString(), anyString(), anyString(), any());
        verify(quizRepository, never()).saveAndFlush(any());
    }

    @Test
    void 전역_requestId_고유_제약_예외는_잡지_않고_올려_Quiz_요청을_만들지_않는다() {
        willThrow(new DataIntegrityViolationException("duplicate request")).given(earnRequestRepository)
                .insertPending(anyString(), anyString(), anyString(), any());

        assertThatThrownBy(() -> store.insert(command)).isInstanceOf(DataIntegrityViolationException.class);
        verify(quizRepository, never()).saveAndFlush(any());
    }

    @Test
    void Quiz_요청_고유_제약_예외도_잡지_않고_올려_먼저_만든_전역_선점까지_함께_롤백하게_한다() {
        willThrow(new DataIntegrityViolationException("duplicate attempt")).given(quizRepository)
                .saveAndFlush(any(TicketQuizRewardRequest.class));

        assertThatThrownBy(() -> store.insert(command)).isInstanceOf(DataIntegrityViolationException.class);
        verify(earnRequestRepository).insertPending(anyString(), anyString(), anyString(), any());
    }

    @Test
    void 같은_rewardRequestId와_같은_입력은_재요청이다() {
        TicketEarnRequest claim = claim(command.computeFingerprint(), "QUIZ");
        given(quizRepository.findByRewardRequestId(REQUEST_ID.toString())).willReturn(Optional.of(stored(command, 5L)));
        given(earnRequestRepository.findByRequestId(REQUEST_ID.toString())).willReturn(Optional.of(claim));

        assertThat(store.findExisting(command))
                .contains(QuizRewardAdmission.replayed(5L, QuizRewardRequestStatus.RECEIVED));
    }

    @Test
    void 같은_rewardRequestId에_다른_입력은_충돌이다() {
        given(quizRepository.findByRewardRequestId(REQUEST_ID.toString())).willReturn(Optional.of(stored(command, 5L)));

        for (QuizRewardCommand other : new QuizRewardCommand[] {
                new QuizRewardCommand(100L, 7L, 10L, 2, REQUEST_ID),
                new QuizRewardCommand(101L, 7L, 10L, 3, REQUEST_ID),
                new QuizRewardCommand(100L, 8L, 10L, 3, REQUEST_ID),
                new QuizRewardCommand(100L, 7L, 11L, 3, REQUEST_ID)}) {
            assertThat(store.findExisting(other))
                    .contains(QuizRewardAdmission.conflict(QuizRewardAdmission.Outcome.REQUEST_ID_CONFLICT));
        }
    }

    @Test
    void Quiz_요청_없이_전역_EARN_request만_있으면_재요청이_아니라_충돌이다() {
        given(quizRepository.findByRewardRequestId(REQUEST_ID.toString())).willReturn(Optional.empty());
        for (TicketEarnRequest claim : new TicketEarnRequest[] {
                claim("f".repeat(64), "DAILY"), claim(command.computeFingerprint(), "QUIZ")}) {
            given(earnRequestRepository.findByRequestId(REQUEST_ID.toString())).willReturn(Optional.of(claim));

            assertThat(store.findExisting(command))
                    .contains(QuizRewardAdmission.conflict(QuizRewardAdmission.Outcome.REQUEST_ID_CONFLICT));
        }
    }

    @Test
    void 같은_attempt가_다른_rewardRequestId로_접수돼_있으면_충돌이고_기존_요청은_그대로다() {
        TicketQuizRewardRequest existing = stored(new QuizRewardCommand(100L, 7L, 10L, 3, UUID.randomUUID()), 5L);
        given(quizRepository.findByAttemptId(100L)).willReturn(Optional.of(existing));

        assertThat(store.findExisting(command))
                .contains(QuizRewardAdmission.conflict(QuizRewardAdmission.Outcome.ATTEMPT_CONFLICT));
        assertThat(existing.getStatus()).isEqualTo(QuizRewardRequestStatus.RECEIVED);
        verify(quizRepository, never()).saveAndFlush(any());
    }

    @Test
    void 처음_보는_요청이면_빈_값이다() {
        assertThat(store.findExisting(command)).isEmpty();
    }

    @Test
    void Quiz_요청에_맞는_전역_EARN_request가_없으면_정합성_오류다() {
        given(quizRepository.findByRewardRequestId(REQUEST_ID.toString())).willReturn(Optional.of(stored(command, 5L)));

        assertThatThrownBy(() -> store.findExisting(command)).isInstanceOf(IllegalStateException.class);

        TicketEarnRequest missionClaim = claim(command.computeFingerprint(), "DAILY");
        given(earnRequestRepository.findByRequestId(REQUEST_ID.toString())).willReturn(Optional.of(missionClaim));
        assertThatThrownBy(() -> store.findExisting(command)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 접수는_한_Transaction이고_조회는_읽기_전용이며_Mission_선점은_여전히_REQUIRES_NEW다() throws Exception {
        Transactional insert = QuizRewardRequestStore.class.getMethod("insert", QuizRewardCommand.class)
                .getAnnotation(Transactional.class);
        assertThat(insert.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(insert.readOnly()).isFalse();
        Transactional findExisting = QuizRewardRequestStore.class.getMethod("findExisting", QuizRewardCommand.class)
                .getAnnotation(Transactional.class);
        assertThat(findExisting.readOnly()).isTrue();
        // 두 테이블을 같은 스냅샷에서 읽도록 DB 기본 격리 수준과 관계없이 REPEATABLE_READ다.
        assertThat(findExisting.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
        assertThat(TicketEarnRequestClaimService.class
                .getMethod("claim", kr.co.cking.ticket.application.dto.EarnCommand.class)
                .getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    private static TicketQuizRewardRequest stored(QuizRewardCommand command, Long id) {
        TicketQuizRewardRequest request = new TicketQuizRewardRequest(command.rewardRequestId().toString(),
                command.attemptId(), command.memberId(), command.creatorId(), command.correctCount(),
                QuizRewardCommand.FINGERPRINT_VERSION, command.computeFingerprint(), NOW);
        ReflectionTestUtils.setField(request, "quizRewardRequestId", id);
        return request;
    }

    private static TicketEarnRequest claim(String fingerprint, String rewardPolicy) {
        TicketEarnRequest claim = mock(TicketEarnRequest.class);
        given(claim.hasSameRequest(fingerprint, "QUIZ")).willReturn("QUIZ".equals(rewardPolicy));
        given(claim.getRewardPolicy()).willReturn(rewardPolicy);
        return claim;
    }
}
