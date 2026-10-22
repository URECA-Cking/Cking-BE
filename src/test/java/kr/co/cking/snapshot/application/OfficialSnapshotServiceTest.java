package kr.co.cking.snapshot.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.event.domain.EventStatus;
import kr.co.cking.snapshot.domain.CandidateValue;
import kr.co.cking.snapshot.domain.DrawSnapshot;
import kr.co.cking.snapshot.domain.PrizeValue;
import kr.co.cking.snapshot.domain.SnapshotErrorCode;
import kr.co.cking.snapshot.repository.DrawSnapshotRepository;
import kr.co.cking.snapshot.repository.SnapshotEventSource;
import kr.co.cking.snapshot.repository.SnapshotSourceQueryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OfficialSnapshotServiceTest {

    private static final String HASH = "a".repeat(64);

    @Mock
    private DrawSnapshotRepository snapshotRepository;

    @Mock
    private SnapshotSourceQueryRepository sourceQueryRepository;

    @Mock
    private SnapshotHashGenerator hashGenerator;

    @Mock
    private SnapshotHashV2Generator hashV2Generator;

    private OfficialSnapshotService service;

    @BeforeEach
    void setUp() {
        service = new OfficialSnapshotService(
                snapshotRepository, sourceQueryRepository, hashGenerator, hashV2Generator);
    }

    @Test
    void CLOSED_이벤트의_공식_Snapshot을_생성한다() {
        List<CandidateValue> candidates = List.of(
                new CandidateValue(1L, 3L),
                new CandidateValue(2L, 7L)
        );
        when(sourceQueryRepository.findEventForUpdate(10L))
                .thenReturn(Optional.of(new SnapshotEventSource(10L, EventStatus.CLOSED, 2, "WEIGHTED")));
        when(snapshotRepository.findByEventId(10L)).thenReturn(Optional.empty());
        when(sourceQueryRepository.findCandidates(10L)).thenReturn(candidates);
        when(hashGenerator.generate(any())).thenReturn(new SnapshotHash("payload", HASH));
        when(snapshotRepository.saveAndFlush(any(DrawSnapshot.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OfficialSnapshotResult result = service.createIfAbsent(10L);

        assertThat(result.eventId()).isEqualTo(10L);
        assertThat(result.candidateCount()).isEqualTo(2);
        assertThat(result.totalTicketCount()).isEqualTo(10L);
        assertThat(result.snapshotHash()).isEqualTo(HASH);
        verify(snapshotRepository).saveAndFlush(any(DrawSnapshot.class));
    }

    @ParameterizedTest
    @CsvSource({
            "UNIFORM, PRIZE_UNIFORM_V1, UNIFORM_V1",
            "UNIFORM, PRIZE_WEIGHTED_V1, UNIFORM_V1",
            "WEIGHTED, PRIZE_UNIFORM_V1, WEIGHTED_V1",
            "WEIGHTED, PRIZE_WEIGHTED_V1, WEIGHTED_V1"
    })
    void Event의_네_가지_알고리즘_조합을_Snapshot에_확정한다(
            String drawMethod,
            String prizeAlgorithmVersion,
            String expectedAlgorithmVersion
    ) {
        when(sourceQueryRepository.findEventForUpdate(10L)).thenReturn(Optional.of(
                new SnapshotEventSource(10L, EventStatus.CLOSED, 1, drawMethod, prizeAlgorithmVersion)));
        when(snapshotRepository.findByEventId(10L)).thenReturn(Optional.empty());
        when(sourceQueryRepository.findCandidates(10L))
                .thenReturn(List.of(new CandidateValue(1L, 3L)));
        when(sourceQueryRepository.findPrizes(10L))
                .thenReturn(List.of(new PrizeValue("A", "A", 1, 1L, 1)));
        when(hashV2Generator.generate(any())).thenReturn(new SnapshotHash("payload", HASH));
        when(snapshotRepository.saveAndFlush(any(DrawSnapshot.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        OfficialSnapshotResult result = service.createIfAbsent(10L);

        assertThat(result.drawMethod()).isEqualTo(drawMethod);
        assertThat(result.algorithmVersion()).isEqualTo(expectedAlgorithmVersion);
        assertThat(result.prizeAlgorithmVersion()).isEqualTo(prizeAlgorithmVersion);
    }

    @Test
    void 기존_Snapshot이_있으면_다시_생성하지_않는다() {
        DrawSnapshot existing = DrawSnapshot.create(
                10L,
                1,
                "WEIGHTED",
                "WEIGHTED_V1",
                HASH,
                List.of(new CandidateValue(1L, 3L))
        );
        when(sourceQueryRepository.findEventForUpdate(10L))
                .thenReturn(Optional.of(new SnapshotEventSource(
                        10L,
                        EventStatus.DRAW_COMPLETED,
                        1,
                        "WEIGHTED"
                )));
        when(snapshotRepository.findByEventId(10L)).thenReturn(Optional.of(existing));

        OfficialSnapshotResult result = service.createIfAbsent(10L);

        assertThat(result.eventId()).isEqualTo(10L);
        verify(snapshotRepository, never()).saveAndFlush(any());
    }

    @Test
    void CLOSED가_아닌_이벤트는_Snapshot을_생성하지_않는다() {
        when(sourceQueryRepository.findEventForUpdate(10L))
                .thenReturn(Optional.of(new SnapshotEventSource(10L, EventStatus.CLOSING, 1, "WEIGHTED")));
        when(snapshotRepository.findByEventId(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createIfAbsent(10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(SnapshotErrorCode.EVENT_NOT_CLOSED));
        verify(snapshotRepository, never()).saveAndFlush(any());
    }

    @Test
    void 존재하지_않는_이벤트는_도메인_오류를_반환한다() {
        when(sourceQueryRepository.findEventForUpdate(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createIfAbsent(10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(SnapshotErrorCode.EVENT_NOT_FOUND));
    }
}
