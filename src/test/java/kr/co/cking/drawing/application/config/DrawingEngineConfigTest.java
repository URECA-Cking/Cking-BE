package kr.co.cking.drawing.application.config;

import static org.assertj.core.api.Assertions.assertThat;

import kr.co.cking.drawing.domain.engine.DrawingEngine;
import kr.co.cking.drawing.domain.engine.WeightedV1DrawingEngine;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DrawingEngineConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DrawingEngineConfig.class);

    @Test
    void DrawingEngine_인터페이스로_WEIGHTED_V1_엔진을_주입한다() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(DrawingEngine.class);
            assertThat(context.getBean(DrawingEngine.class))
                    .isExactlyInstanceOf(WeightedV1DrawingEngine.class);
        });
    }
}
