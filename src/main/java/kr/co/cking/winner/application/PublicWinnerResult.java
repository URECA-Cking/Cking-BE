package kr.co.cking.winner.application;

import kr.co.cking.member.application.MemberInfo;
import kr.co.cking.support.PersonalInfoMasker;
import kr.co.cking.winner.domain.Winner;

/** 공개 API에 노출할 마스킹된 Winner 정보다. */
public record PublicWinnerResult(
        Long winnerId,
        Long drawingId,
        String name,
        String phone,
        int rankInDrawing
) {

    /** Winner와 회원 원본 정보를 공개용 마스킹 응답으로 변환한다. */
    public static PublicWinnerResult from(Winner winner, MemberInfo member) {
        return new PublicWinnerResult(
                winner.getId(),
                winner.getDrawingId(),
                PersonalInfoMasker.maskName(member.name()),
                PersonalInfoMasker.maskPhone(member.phone()),
                winner.getRankInDrawing()
        );
    }
}
