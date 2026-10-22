package kr.co.cking.drawing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.drawing.domain.DrawSeed;
import kr.co.cking.drawing.domain.DrawingErrorCode;
import kr.co.cking.drawing.domain.seed.DrawingSeed;
import kr.co.cking.drawing.repository.DrawSeedRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DrawingSeedServiceTest {

    private static final DrawingSeed FIRST_SEED = DrawingSeed.from("01".repeat(32));
    private static final DrawingSeed SECOND_SEED = DrawingSeed.from("02".repeat(32));

    @Mock
    private DrawSeedRepository drawSeedRepository;

    @Mock
    private DrawingSeedPolicy drawingSeedPolicy;

    private DrawingSeedService service;

    @BeforeEach
    void setUp() {
        service = new DrawingSeedService(drawSeedRepository, drawingSeedPolicy);
    }

    @Test
    void INITIAL은_신규_Seed를_저장하고_ID와_값을_함께_반환한다() {
        when(drawingSeedPolicy.createForInitial()).thenReturn(FIRST_SEED);
        when(drawSeedRepository.save(any(DrawSeed.class)))
                .thenAnswer(invocation -> withId(invocation.getArgument(0), 10L));

        PersistedDrawingSeed result = service.createForInitial();

        assertThat(result.seedId()).isEqualTo(10L);
        assertThat(result.seed()).isEqualTo(FIRST_SEED);
    }

    @Test
    void Retry는_저장된_Seed를_재사용하고_새_행을_저장하지_않는다() {
        DrawSeed stored = withId(DrawSeed.create(FIRST_SEED), 10L);
        when(drawSeedRepository.findById(10L)).thenReturn(Optional.of(stored));
        when(drawingSeedPolicy.reuseForRetry(FIRST_SEED.value())).thenReturn(FIRST_SEED);

        PersistedDrawingSeed result = service.reuseForRetry(10L);

        assertThat(result).isEqualTo(new PersistedDrawingSeed(10L, FIRST_SEED));
        verify(drawSeedRepository, never()).save(any());
    }

    @Test
    void REDRAW는_이전_Seed를_조회하고_다른_Seed를_신규_저장한다() {
        DrawSeed previous = withId(DrawSeed.create(FIRST_SEED), 10L);
        when(drawSeedRepository.findById(10L)).thenReturn(Optional.of(previous));
        when(drawingSeedPolicy.reuseForRetry(FIRST_SEED.value())).thenReturn(FIRST_SEED);
        when(drawingSeedPolicy.createForRedraw(FIRST_SEED)).thenReturn(SECOND_SEED);
        when(drawSeedRepository.save(any(DrawSeed.class)))
                .thenAnswer(invocation -> withId(invocation.getArgument(0), 11L));

        PersistedDrawingSeed result = service.createForRedraw(10L);

        assertThat(result).isEqualTo(new PersistedDrawingSeed(11L, SECOND_SEED));
        verify(drawingSeedPolicy).createForRedraw(FIRST_SEED);
    }

    @Test
    void 존재하지_않는_Seed_ID는_업무_예외로_차단한다() {
        when(drawSeedRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reuseForRetry(999L))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(DrawingErrorCode.DRAWING_SEED_NOT_FOUND));
    }

    @Test
    void 유효하지_않은_Seed_ID는_조회_전에_차단한다() {
        assertThatThrownBy(() -> service.reuseForRetry(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reuseForRetry(0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private DrawSeed withId(DrawSeed seed, long id) {
        ReflectionTestUtils.setField(seed, "id", id);
        return seed;
    }
}
