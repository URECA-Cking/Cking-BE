package kr.co.cking.redraw.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import kr.co.cking.redraw.repository.RedrawRequestVacancyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** RedrawRequest와 Vacancy 저장의 트랜잭션 원자성 및 실패 후 멱등 재시도를 실제 DB로 검증한다. */
@SpringBootTest
class RedrawRequestCreateRollbackIntegrationTest extends RedrawRequestCreateIntegrationFixture {

    @MockitoSpyBean
    private RedrawRequestVacancyRepository redrawRequestVacancyRepository;

    /** Vacancy 저장 실패는 Request까지 롤백하고 같은 멱등 키의 복구 재시도를 허용한다. */
    @Test
    void Vacancy_저장_실패_후_같은_멱등_키로_재시도할_수_있다() {
        String idempotencyKey = newIdempotencyKey();
        doThrow(new RuntimeException("Vacancy 저장 강제 실패"))
                .when(redrawRequestVacancyRepository).saveAll(any());

        assertThatThrownBy(() -> redrawRequestCreateService.create(command(idempotencyKey)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("강제 실패");
        assertThat(redrawRequestCount()).isZero();
        assertThat(vacancyCount()).isZero();

        reset(redrawRequestVacancyRepository);

        RedrawRequestCreateResult retry = redrawRequestCreateService.create(command(idempotencyKey));

        assertThat(retry.created()).isTrue();
        assertThat(redrawRequestCount()).isEqualTo(1);
        assertThat(vacancyCount()).isEqualTo(1);
    }
}
