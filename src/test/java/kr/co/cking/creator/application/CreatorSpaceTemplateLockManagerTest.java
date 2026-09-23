package kr.co.cking.creator.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@ExtendWith(OutputCaptureExtension.class)
class CreatorSpaceTemplateLockManagerTest {

    private static final String KEY = "creator-space-template:activation";

    /** advisory lock 해제 실패는 운영자가 식별할 수 있도록 경고 로그를 남기는지 검증한다. */
    @Test
    void failedLockReleaseLogsWarning(CapturedOutput output) {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        given(jdbcTemplate.queryForObject("SELECT GET_LOCK(?, 0)", Long.class, KEY)).willReturn(1L);
        given(jdbcTemplate.queryForObject("SELECT RELEASE_LOCK(?)", Long.class, KEY)).willReturn(0L);
        CreatorSpaceTemplateLockManager lockManager = new CreatorSpaceTemplateLockManager(jdbcTemplate);

        assertThatThrownBy(() -> lockManager.execute(() -> "ignored"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(output).contains("Failed to release creator space template advisory lock");
    }
}
