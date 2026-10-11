package kr.co.cking.quiz.grounding;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Grounding 결과 계약을 검증한다. 위반하면 {@code INVALID_RESPONSE}다.
 *
 * <ul>
 *   <li>contentText: 공백만으로 이루어질 수 없고 {@link #MAX_CONTENT_TEXT_LENGTH}자 이하(Java {@code String.length()}).
 *       원문을 바꾸지 않는다</li>
 *   <li>facts: 비어 있어도 된다. 각 사실과 근거는 공백일 수 없다</li>
 *   <li>시각: 시작·끝이 모두 null이거나 모두 {@code MM:SS}/{@code MM:SS.s}(분 2자리 이상, 초 00~59)이고,
 *       시작이 끝보다 늦을 수 없다. 영상 길이는 알 수 없어 검사하지 않는다</li>
 * </ul>
 */
public class VideoGroundingValidator {

    /** Quiz 생성 입력 제한({@code QuizGenerationValidator})과 같은 값이다. */
    public static final int MAX_CONTENT_TEXT_LENGTH = 50_000;

    private static final Pattern TIMESTAMP = Pattern.compile("(\\d{2,}):([0-5]\\d)(?:\\.(\\d))?");

    public VideoGrounding validate(VideoGrounding grounding) {
        String contentText = grounding.contentText();
        if (contentText == null || contentText.isBlank() || contentText.length() > MAX_CONTENT_TEXT_LENGTH) {
            throw invalid();
        }
        for (GroundingFact fact : grounding.facts()) {
            validateFact(fact);
        }
        return grounding;
    }

    private void validateFact(GroundingFact fact) {
        if (fact == null || isBlank(fact.fact()) || isBlank(fact.evidence()) || fact.evidenceType() == null) {
            throw invalid();
        }
        if (fact.timestampStart() == null && fact.timestampEnd() == null) {
            return;
        }
        if (fact.timestampStart() == null || fact.timestampEnd() == null
                || seconds(fact.timestampStart()).compareTo(seconds(fact.timestampEnd())) > 0) {
            throw invalid();
        }
    }

    private BigDecimal seconds(String timestamp) {
        Matcher matcher = TIMESTAMP.matcher(timestamp);
        if (!matcher.matches()) {
            throw invalid();
        }
        BigDecimal minutes = new BigDecimal(matcher.group(1));
        BigDecimal seconds = new BigDecimal(matcher.group(2) + "." + (matcher.group(3) == null ? "0" : matcher.group(3)));
        return minutes.multiply(BigDecimal.valueOf(60)).add(seconds);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private VideoGroundingException invalid() {
        return new VideoGroundingException(VideoGroundingException.Failure.INVALID_RESPONSE);
    }
}
