package kr.co.cking.snapshot.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
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
class SnapshotIntegrityServiceTest {

    private static final long EVENT_ID = 10L;
    private static final long SNAPSHOT_ID = 20L;

    @Mock
    private DrawSnapshotRepository snapshotRepository;

    @Mock
    private DrawSnapshotCandidateRepository candidateRepository;

    private final SnapshotHashGenerator hashGenerator = new SnapshotHashGenerator();

    private SnapshotIntegrityService service;

    @BeforeEach
    void setUp() {
        service = new SnapshotIntegrityService(snapshotRepository, candidateRepository, hashGenerator);
    }

    @Test
    void Hash가_일치하면_불변_VerifiedSnapshot을_반환한다() {
        List<CandidateValue> values = List.of(
                new CandidateValue(1L, 3L),
                new CandidateValue(2L, 7L)
        );
        DrawSnapshot snapshot = snapshot(values, hash(values));
        List<DrawSnapshotCandidate> candidateEntities = candidateEntities(values);
        when(snapshotRepository.findByEventId(EVENT_ID)).thenReturn(Optional.of(snapshot));
        when(candidateRepository.findAllBySnapshot_IdOrderByMemberIdAsc(SNAPSHOT_ID))
                .thenReturn(candidateEntities);

        VerifiedSnapshot verified = service.verifyForDrawing(EVENT_ID);

        assertThat(verified.snapshotId()).isEqualTo(SNAPSHOT_ID);
        assertThat(verified.eventId()).isEqualTo(EVENT_ID);
        assertThat(verified.candidates()).containsExactlyElementsOf(values);
        assertThatThrownBy(() -> verified.candidates().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void VerifiedSnapshot은_외부에서_생성하거나_확장할_수_없다() {
        assertThat(Modifier.isFinal(VerifiedSnapshot.class.getModifiers())).isTrue();
        assertThat(VerifiedSnapshot.class.getConstructors()).isEmpty();
        assertThat(VerifiedSnapshot.class.getDeclaredConstructors())
                .allSatisfy(constructor -> assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue());
    }

    @Test
    void 저장된_Hash와_재계산_Hash가_다르면_검증에_실패한다() {
        List<CandidateValue> values = List.of(new CandidateValue(1L, 3L));
        DrawSnapshot snapshot = snapshot(values, "a".repeat(64));
        List<DrawSnapshotCandidate> candidateEntities = candidateEntities(values);
        when(snapshotRepository.findByEventId(EVENT_ID)).thenReturn(Optional.of(snapshot));
        when(candidateRepository.findAllBySnapshot_IdOrderByMemberIdAsc(SNAPSHOT_ID))
                .thenReturn(candidateEntities);

        assertThatThrownBy(() -> service.verifyForDrawing(EVENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(SnapshotErrorCode.SNAPSHOT_HASH_MISMATCH));
    }

    @Test
    void 후보_집계값이_저장된_값과_다르면_검증에_실패한다() {
        List<CandidateValue> storedValues = List.of(new CandidateValue(1L, 3L));
        List<CandidateValue> changedValues = List.of(new CandidateValue(1L, 4L));
        DrawSnapshot snapshot = snapshot(storedValues, hash(changedValues));
        List<DrawSnapshotCandidate> candidateEntities = candidateEntities(changedValues);
        when(snapshotRepository.findByEventId(EVENT_ID)).thenReturn(Optional.of(snapshot));
        when(candidateRepository.findAllBySnapshot_IdOrderByMemberIdAsc(SNAPSHOT_ID))
                .thenReturn(candidateEntities);

        assertThatThrownBy(() -> service.verifyForDrawing(EVENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(SnapshotErrorCode.SNAPSHOT_HASH_MISMATCH));
    }

    @Test
    void 공식_Snapshot이_없으면_도메인_오류를_반환한다() {
        when(snapshotRepository.findByEventId(EVENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyForDrawing(EVENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(SnapshotErrorCode.SNAPSHOT_NOT_FOUND));
    }

    private DrawSnapshot snapshot(List<CandidateValue> candidates, String snapshotHash) {
        DrawSnapshot snapshot = DrawSnapshot.create(
                EVENT_ID,
                2,
                "WEIGHTED",
                "WEIGHTED_V1",
                snapshotHash,
                candidates
        );
        ReflectionTestUtils.setField(snapshot, "id", SNAPSHOT_ID);
        return snapshot;
    }

    private String hash(List<CandidateValue> candidates) {
        return hashGenerator.generate(new SnapshotHashInput(
                EVENT_ID,
                2,
                "WEIGHTED",
                "WEIGHTED_V1",
                candidates
        )).value();
    }

    private List<DrawSnapshotCandidate> candidateEntities(List<CandidateValue> values) {
        return values.stream()
                .map(value -> {
                    DrawSnapshotCandidate candidate = mock(DrawSnapshotCandidate.class);
                    when(candidate.getMemberId()).thenReturn(value.memberId());
                    when(candidate.getTicketCount()).thenReturn(value.ticketCount());
                    return candidate;
                })
                .toList();
    }
}
