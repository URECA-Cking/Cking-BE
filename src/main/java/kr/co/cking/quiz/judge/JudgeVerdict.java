package kr.co.cking.quiz.judge;

/** rubric 항목 하나의 판정. 근거가 부족해 판정할 수 없으면 {@link #UNCERTAIN}이다. */
public enum JudgeVerdict {

    PASS("pass"),
    FAIL("fail"),
    UNCERTAIN("uncertain");

    private final String value;

    JudgeVerdict(String value) {
        this.value = value;
    }

    /** Provider 응답 schema와 hash에 쓰는 값. */
    public String value() {
        return value;
    }

    /** schema 값과 정확히 같을 때만 판정으로 바꾼다. 대소문자가 다르거나 없는 값이면 null이다. */
    public static JudgeVerdict fromValue(String value) {
        for (JudgeVerdict verdict : values()) {
            if (verdict.value.equals(value)) {
                return verdict;
            }
        }
        return null;
    }
}
