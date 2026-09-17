package kr.co.cking.mission;

import kr.co.cking.mission.application.MissionCompletionRecorder;
import kr.co.cking.mission.repository.MissionCompletionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MissionCompletionRecorderTest {

    private final MissionCompletionRepository repository = mock(MissionCompletionRepository.class);
    private final MissionCompletionRecorder recorder = new MissionCompletionRecorder(repository);

    @Test
    void 저장에_성공하면_예외_없이_반환한다() {
        assertThatCode(() ->
                recorder.tryInsert(1L, 10L, 100L, "2026-09-16", UUID.randomUUID(), LocalDateTime.now()))
                .doesNotThrowAnyException();
    }

    @Test
    void UNIQUE_제약_위반이면_예외를_그대로_전파한다() {
        // 이 메서드 안에서 예외를 잡으면 REQUIRES_NEW 트랜잭션이 rollback-only로 표시된 채
        // 정상 커밋을 시도해 UnexpectedRollbackException이 나므로, 잡지 않고 그대로 전파해야 한다.
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("unique violation"));

        assertThatThrownBy(() ->
                recorder.tryInsert(1L, 10L, 100L, "2026-09-16", UUID.randomUUID(), LocalDateTime.now()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
