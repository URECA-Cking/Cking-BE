package kr.co.cking.post.filter;

import kr.co.cking.post.domain.CommentFilterAction;

import java.util.List;
import java.util.Objects;

/** 필터 서비스가 돌려준 판정. 규칙·모델 버전은 판정을 다시 해석할 수 있도록 반드시 함께 받는다. */
public record CommentFilterResult(
        CommentFilterAction action, List<String> reasons, String ruleVersion, String modelVersion) {

    public CommentFilterResult {
        Objects.requireNonNull(action, "action");
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        requireText(ruleVersion, "ruleVersion");
        requireText(modelVersion, "modelVersion");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "이 비어 있습니다.");
        }
    }
}
