package kr.co.cking.winner.application;

import kr.co.cking.member.application.MemberInfo;
import kr.co.cking.support.PersonalInfoMasker;
import kr.co.cking.drawing.domain.DrawingType;
import kr.co.cking.winner.repository.PublicWinnerProjection;

/** 공개 API에 노출할 마스킹된 Winner 정보다. */
public record PublicWinnerResult(
        Long winnerId,
        Long drawingId,
        int drawNo,
        DrawingType drawType,
        String name,
        String phone,
        int rankInDrawing
) {

    /** 공개 Winner Projection과 회원 원본 정보를 마스킹된 이력 식별 응답으로 변환한다. */
    public static PublicWinnerResult from(PublicWinnerProjection winner, MemberInfo member) {
        return new PublicWinnerResult(
                winner.winnerId(),
                winner.drawingId(),
                winner.drawNo(),
                winner.drawType(),
                PersonalInfoMasker.maskName(member.name()),
                PersonalInfoMasker.maskPhone(member.phone()),
                winner.rankInDrawing()
        );
    }
}
