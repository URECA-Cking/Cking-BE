package kr.co.cking.drawing.domain.engine;

public record DrawWinner(Long memberId, int rank, long appliedTicketCount) {

    public DrawWinner {
        if (memberId == null || memberId <= 0) {
            throw new IllegalArgumentException("memberId는 양수여야 합니다.");
        }
        if (rank <= 0) {
            throw new IllegalArgumentException("rank는 양수여야 합니다.");
        }
        if (appliedTicketCount <= 0) {
            throw new IllegalArgumentException("appliedTicketCount는 양수여야 합니다.");
        }
    }
}
