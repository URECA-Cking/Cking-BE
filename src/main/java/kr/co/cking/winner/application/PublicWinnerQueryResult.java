package kr.co.cking.winner.application;

import java.util.List;

/** 공개 Winner 조회 API가 반환하는 Event별 당첨자 목록이다. */
public record PublicWinnerQueryResult(Long eventId, List<PublicWinnerResult> winners) {

    /** 변경 불가능한 목록으로 결과를 만들어 호출자가 응답 내용을 바꾸지 못하게 한다. */
    public PublicWinnerQueryResult {
        winners = List.copyOf(winners);
    }
}
