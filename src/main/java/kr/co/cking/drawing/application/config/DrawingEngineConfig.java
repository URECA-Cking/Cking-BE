package kr.co.cking.drawing.application.config;

import java.util.Map;
import kr.co.cking.drawing.domain.engine.DrawingAlgorithmVersion;
import kr.co.cking.drawing.domain.engine.DrawingEngine;
import kr.co.cking.drawing.domain.engine.ResolvingDrawingEngine;
import kr.co.cking.drawing.domain.engine.UniformV1DrawingEngine;
import kr.co.cking.drawing.domain.engine.WeightedV1DrawingEngine;
import kr.co.cking.drawing.domain.hash.DrawInputHashGenerator;
import kr.co.cking.drawing.domain.hash.DrawResultHashGenerator;
import kr.co.cking.drawing.domain.hash.DrawInputV2HashGenerator;
import kr.co.cking.drawing.domain.hash.DrawResultV2HashGenerator;
import kr.co.cking.drawing.domain.prize.PrizeAllocationEngine;
import kr.co.cking.drawing.domain.prize.PrizeAllocationAlgorithmVersion;
import kr.co.cking.drawing.domain.prize.ResolvingPrizeAllocationEngine;
import kr.co.cking.drawing.domain.prize.UniformPrizeV1AllocationEngine;
import kr.co.cking.drawing.domain.prize.WeightedPrizeV1AllocationEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 순수 추첨 엔진과 Spring 애플리케이션 계층 사이의 조립 책임. */
@Configuration
public class DrawingEngineConfig {

    @Bean
    public DrawingEngine drawingEngine() {
        return new ResolvingDrawingEngine(Map.of(
                DrawingAlgorithmVersion.UNIFORM_V1, new UniformV1DrawingEngine(),
                DrawingAlgorithmVersion.WEIGHTED_V1, new WeightedV1DrawingEngine()
        ));
    }

    @Bean
    public DrawInputHashGenerator drawInputHashGenerator() {
        return new DrawInputHashGenerator();
    }

    @Bean
    public DrawResultHashGenerator drawResultHashGenerator() {
        return new DrawResultHashGenerator();
    }

    @Bean
    public PrizeAllocationEngine prizeAllocationEngine() {
        return new ResolvingPrizeAllocationEngine(Map.of(
                PrizeAllocationAlgorithmVersion.PRIZE_UNIFORM_V1, new UniformPrizeV1AllocationEngine(),
                PrizeAllocationAlgorithmVersion.PRIZE_WEIGHTED_V1, new WeightedPrizeV1AllocationEngine()
        ));
    }

    @Bean
    public DrawInputV2HashGenerator drawInputV2HashGenerator() {
        return new DrawInputV2HashGenerator();
    }

    @Bean
    public DrawResultV2HashGenerator drawResultV2HashGenerator() {
        return new DrawResultV2HashGenerator();
    }
}
