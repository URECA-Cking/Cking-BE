package kr.co.cking.subscriptionverification.application.vision;

/** 구독 인증 이미지를 분석하는 Provider 독립 Port다. */
@FunctionalInterface
public interface VisionAnalysisPort {

    VisionAnalysisResult analyze(VisionAnalysisRequest request);
}
