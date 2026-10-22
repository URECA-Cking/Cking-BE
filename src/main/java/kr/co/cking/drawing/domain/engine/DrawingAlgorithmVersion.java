package kr.co.cking.drawing.domain.engine;

public enum DrawingAlgorithmVersion {

    UNIFORM_V1,
    WEIGHTED_V1;

    public static DrawingAlgorithmVersion from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("algorithmVersion은 필수입니다.");
        }

        try {
            return valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("지원하지 않는 algorithmVersion입니다: " + value, exception);
        }
    }
}
