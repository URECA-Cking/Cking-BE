package kr.co.cking.post.filter;

import kr.co.cking.post.domain.CommentFilterAction;

import java.util.List;
import java.util.Objects;

/**
 * 필터 서비스가 돌려준 판정. 규칙·모델 버전은 판정을 다시 해석할 수 있도록 반드시 함께 받는다.
 *
 * <p>BLOCK은 사유가 1개 이상이어야 한다. 사유로 개인정보(privacy:*) 여부를 가려 원문 보기를 막으므로, 사유가 없는 BLOCK을
 * 받아들이면 이전의 개인정보 사유를 지우고 원문을 열어 줄 수 있다. PASS는 사유가 비어 있어도 된다.
 */
public record CommentFilterResult(
        CommentFilterAction action, List<String> reasons, String ruleVersion, String modelVersion) {

    public CommentFilterResult {
        Objects.requireNonNull(action, "action");
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        if (action == CommentFilterAction.BLOCK && reasons.isEmpty()) {
            throw new IllegalArgumentException("BLOCK 판정에는 사유가 1개 이상 필요합니다.");
        }
        requireText(ruleVersion, "ruleVersion");
        requireText(modelVersion, "modelVersion");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "이 비어 있습니다.");
        }
    }
}
