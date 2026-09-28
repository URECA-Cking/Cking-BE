package kr.co.cking.mission;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MissionCompletionTest {

    @Test
    void completionKey가_없으면_periodKey를_완료키로_사용한다() {
        MissionCompletion completion = MissionCompletion.builder()
                .periodKey("2026-09-16")
                .build();

        assertThat(completion.getCompletionKey()).isEqualTo("2026-09-16");
    }
}
