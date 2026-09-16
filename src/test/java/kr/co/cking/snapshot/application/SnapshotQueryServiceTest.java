package kr.co.cking.snapshot.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.member.application.MemberQueryService;
import kr.co.cking.snapshot.domain.CandidateValue;
import kr.co.cking.snapshot.domain.DrawSnapshot;
import kr.co.cking.snapshot.domain.DrawSnapshotCandidate;
import kr.co.cking.snapshot.domain.SnapshotErrorCode;
import kr.co.cking.snapshot.repository.DrawSnapshotCandidateRepository;
import kr.co.cking.snapshot.repository.DrawSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SnapshotQueryServiceTest {

    private static final long EVENT_ID = 10L;
    private static final long SNAPSHOT_ID = 20L;
    private static final long ADMIN_ID = 30L;

    @Mock
    private MemberQueryService memberQueryService;

    @Mock
    private DrawSnapshotRepository snapshotRepository;

    @Mock
    private DrawSnapshotCandidateRepository candidateRepository;

    private SnapshotQueryService service;

    @BeforeEach
    void setUp() {
        service = new SnapshotQueryService(memberQueryService, snapshotRepository, candidateRepository);
    }

    @Test
    void 관리자는_Snapshot과_userId_ASC_후보를_조회한다() {
        DrawSnapshot snapshot = snapshot();
        DrawSnapshotCandidate first = candidate(1L, 3L);
        DrawSnapshotCandidate second = candidate(2L, 7L);
        when(snapshotRepository.findByEventId(EVENT_ID)).thenReturn(Optional.of(snapshot));
        when(candidateRepository.findAllBySnapshot_IdOrderByMemberIdAsc(SNAPSHOT_ID))
                .thenReturn(List.of(second, first));

        SnapshotQueryResult result = service.getOfficialSnapshot(EVENT_ID, ADMIN_ID);

        verify(memberQueryService).validateAdmin(ADMIN_ID);
        assertThat(result.snapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(result.eventId()).isEqualTo(EVENT_ID);
        assertThat(result.winnerCount()).isEqualTo(2);
        assertThat(result.drawMethod()).isEqualTo("WEIGHTED");
        assertThat(result.algorithmVersion()).isEqualTo("WEIGHTED_V1");
        assertThat(result.candidateCount()).isEqualTo(2);
        assertThat(result.totalTicketCount()).isEqualTo(10L);
        assertThat(result.snapshotHash()).isEqualTo("a".repeat(64));
        assertThat(result.createdAt()).isEqualTo(Instant.parse("2026-09-16T00:00:00Z"));
        assertThat(result.candidates()).containsExactly(
                new SnapshotCandidateResult(1L, 3L),
                new SnapshotCandidateResult(2L, 7L)
        );
        assertThatThrownBy(() -> result.candidates().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 공식_Snapshot이_없으면_SNAPSHOT_NOT_FOUND다() {
        when(snapshotRepository.findByEventId(EVENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOfficialSnapshot(EVENT_ID, ADMIN_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(SnapshotErrorCode.SNAPSHOT_NOT_FOUND);
    }

    @Test
    void 관리자_검증에_실패하면_Snapshot을_조회하지_않는다() {
        doThrow(new BusinessException(CommonErrorCode.FORBIDDEN))
                .when(memberQueryService).validateAdmin(ADMIN_ID);

        assertThatThrownBy(() -> service.getOfficialSnapshot(EVENT_ID, ADMIN_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.FORBIDDEN);
        verifyNoInteractions(snapshotRepository, candidateRepository);
    }

    private DrawSnapshot snapshot() {
        DrawSnapshot snapshot = DrawSnapshot.create(
                EVENT_ID,
                2,
                "WEIGHTED",
                "WEIGHTED_V1",
                "a".repeat(64),
                List.of(new CandidateValue(1L, 3L), new CandidateValue(2L, 7L))
        );
        ReflectionTestUtils.setField(snapshot, "id", SNAPSHOT_ID);
        ReflectionTestUtils.setField(snapshot, "createdAt", Instant.parse("2026-09-16T00:00:00Z"));
        return snapshot;
    }

    private DrawSnapshotCandidate candidate(Long memberId, long ticketCount) {
        DrawSnapshotCandidate candidate = mock(DrawSnapshotCandidate.class);
        when(candidate.getMemberId()).thenReturn(memberId);
        when(candidate.getTicketCount()).thenReturn(ticketCount);
        return candidate;
    }
}
