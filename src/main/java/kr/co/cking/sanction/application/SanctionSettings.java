package kr.co.cking.sanction.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * 제재 정책 값(이슈 #569). 운영하면서 조정할 수 있도록 설정으로 둔다.
 *
 * @param warningWindow           경고를 세는 기간. 이 기간보다 오래된 경고는 집계에서 빠지지만 기록은 남는다
 * @param commentRestrictionSteps 경고 횟수별 댓글 작성 제한 기간. 첫 번째가 1회, 두 번째가 2회이며 마지막 값이 그 이상
 *                                횟수에 계속 쓰인다
 */
@Component
public record SanctionSettings(Duration warningWindow, List<Duration> commentRestrictionSteps) {

    @Autowired
    public SanctionSettings(
            @Value("${cking.sanction.warning-window:P90D}") Duration warningWindow,
            @Value("${cking.sanction.comment-restriction-steps:P1D,P3D,P7D}") String commentRestrictionSteps) {
        this(warningWindow, parseSteps(commentRestrictionSteps));
    }

    public SanctionSettings {
        if (warningWindow == null || warningWindow.isNegative() || warningWindow.isZero()) {
            throw new IllegalArgumentException("Sanction warning window must be positive");
        }
        if (commentRestrictionSteps == null || commentRestrictionSteps.isEmpty()
                || commentRestrictionSteps.stream().anyMatch(step -> step == null || step.isNegative() || step.isZero())) {
            throw new IllegalArgumentException("Sanction restriction steps must be a non-empty list of positive durations");
        }
        commentRestrictionSteps = List.copyOf(commentRestrictionSteps);
    }

    /** 집계 기간 안의 경고가 {@code warningCount}회(1 이상)일 때의 댓글 작성 제한 기간. */
    public Duration commentRestrictionFor(long warningCount) {
        if (warningCount < 1) {
            throw new IllegalArgumentException("Warning count must be at least 1");
        }
        int index = (int) Math.min(warningCount, commentRestrictionSteps.size()) - 1;
        return commentRestrictionSteps.get(index);
    }

    private static List<Duration> parseSteps(String steps) {
        if (steps == null || steps.isBlank()) {
            throw new IllegalArgumentException("Sanction restriction steps must not be blank");
        }
        return Arrays.stream(steps.split(",")).map(String::trim).map(Duration::parse).toList();
    }
}
