package kr.co.cking.drawing.application.config;

import kr.co.cking.drawing.domain.engine.DrawingEngine;
import kr.co.cking.drawing.domain.engine.WeightedV1DrawingEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 순수 추첨 엔진과 Spring 애플리케이션 계층 사이의 조립 책임. */
@Configuration
public class DrawingEngineConfig {

    @Bean
    public DrawingEngine drawingEngine() {
        // 현재 지원 알고리즘 WEIGHTED_V1의 명시적 조립.
        return new WeightedV1DrawingEngine();
    }
}
