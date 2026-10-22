package kr.co.cking.redraw.application;

import kr.co.cking.member.application.MemberInfo;
import kr.co.cking.winner.domain.Winner;

/** RedrawRequest가 고정한 결원 Winner의 관리자 조회 정보를 담는다. */
public record RedrawVacancyWinnerResult(
        Long winnerId,
        Long userId,
        String name,
        int rankInDrawing
) {

    /** Winner와 회원 정보를 관리자 상세 응답에 필요한 결원 정보로 변환한다. */
    public static RedrawVacancyWinnerResult from(Winner winner, MemberInfo member) {
        return new RedrawVacancyWinnerResult(
                winner.getId(),
                member.memberId(),
                member.name(),
                winner.getRankInDrawing()
        );
    }
}
