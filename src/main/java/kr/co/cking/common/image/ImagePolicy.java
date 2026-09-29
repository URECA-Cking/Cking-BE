package kr.co.cking.common.image;

/**
 * 도메인별로 다른 이미지 정규화 정책.
 *
 * <p>형식·최대 바이트·최대 픽셀 수는 {@link ImageNormalizer}가 공통으로 적용하고, 최소 해상도와 정규화 후 긴 변
 * 최대값만 도메인이 정한다.
 */
public record ImagePolicy(int minWidth, int minHeight, int maxLongEdge) {

    public ImagePolicy {
        if (minWidth < 1 || minHeight < 1) {
            throw new IllegalArgumentException("최소 해상도는 1 이상이어야 합니다.");
        }
        if (maxLongEdge < Math.max(minWidth, minHeight)) {
            throw new IllegalArgumentException("긴 변 최대값은 최소 해상도보다 작을 수 없습니다.");
        }
    }
}
