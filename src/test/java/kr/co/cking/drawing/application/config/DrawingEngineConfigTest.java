package kr.co.cking.drawing.application.config;

import static org.assertj.core.api.Assertions.assertThat;

import kr.co.cking.drawing.domain.engine.DrawingEngine;
import kr.co.cking.drawing.domain.engine.ResolvingDrawingEngine;
import kr.co.cking.drawing.domain.prize.PrizeAllocationEngine;
import kr.co.cking.drawing.domain.prize.ResolvingPrizeAllocationEngine;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DrawingEngineConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DrawingEngineConfig.class);

    @Test
    void 알고리즘_버전별_Resolver를_주입한다() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(DrawingEngine.class);
            assertThat(context.getBean(DrawingEngine.class))
                    .isExactlyInstanceOf(ResolvingDrawingEngine.class);
            assertThat(context).hasSingleBean(PrizeAllocationEngine.class);
            assertThat(context.getBean(PrizeAllocationEngine.class))
                    .isExactlyInstanceOf(ResolvingPrizeAllocationEngine.class);
        });
    }
}
