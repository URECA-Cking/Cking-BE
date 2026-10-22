package kr.co.cking.snapshot.domain;

import java.util.Comparator;

public record CandidateValue(Long memberId, long ticketCount) {

    public static final Comparator<CandidateValue> BY_MEMBER_ID =
            Comparator.comparing(CandidateValue::memberId);

    public CandidateValue {
        if (memberId == null || memberId <= 0) {
            throw new IllegalArgumentException("memberId는 양수여야 합니다.");
        }
        if (ticketCount <= 0) {
            throw new IllegalArgumentException("ticketCount는 양수여야 합니다.");
        }
    }
}
