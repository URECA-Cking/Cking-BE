package kr.co.cking.event.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntryErrorCodeTest {

    @Test
    void 실패_코드는_동일한_이름의_ErrorCode로_변환된다() {
        assertThat(EntryErrorCode.from(EntryResultCode.EVENT_NOT_OPEN)).isEqualTo(EntryErrorCode.EVENT_NOT_OPEN);
        assertThat(EntryErrorCode.from(EntryResultCode.SYSTEM_ERROR)).isEqualTo(EntryErrorCode.SYSTEM_ERROR);
    }

    @Test
    void SUCCESS나_DUPLICATE_REPLAY는_변환할_수_없다() {
        assertThatThrownBy(() -> EntryErrorCode.from(EntryResultCode.SUCCESS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EntryErrorCode.from(EntryResultCode.DUPLICATE_REPLAY))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
