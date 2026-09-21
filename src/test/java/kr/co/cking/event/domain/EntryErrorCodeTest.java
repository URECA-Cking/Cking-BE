package kr.co.cking.event.domain;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

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

    // docs/domains/event/entry-api.md의 결과코드 표와 같은 값이어야 한다.
    @Test
    void 결과코드별_HTTP_상태는_문서의_확정_매핑과_같다() {
        assertThat(EntryErrorCode.INVALID_TICKET_COUNT.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(EntryErrorCode.EVENT_NOT_OPEN.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(EntryErrorCode.EVENT_CLOSED.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(EntryErrorCode.INSUFFICIENT_BALANCE.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(EntryErrorCode.IDEMPOTENCY_CONFLICT.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(EntryErrorCode.GATE_NOT_LOADED.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(EntryErrorCode.BALANCE_NOT_LOADED.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(EntryErrorCode.SYSTEM_ERROR.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
