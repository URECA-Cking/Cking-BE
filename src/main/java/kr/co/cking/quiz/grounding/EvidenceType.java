package kr.co.cking.quiz.grounding;

/** 사실의 주된 근거. 발화와 화면 어느 쪽인지 판단할 수 없을 때만 {@link #UNKNOWN}이다. */
public enum EvidenceType {
    SPEECH,
    VISUAL,
    UNKNOWN
}
