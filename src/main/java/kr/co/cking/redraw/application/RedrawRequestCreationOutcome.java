package kr.co.cking.redraw.application;

import kr.co.cking.redraw.domain.RedrawRequest;

/** Event 잠금 안에서 확인한 RedrawRequest와 신규 생성 여부를 전달한다. */
record RedrawRequestCreationOutcome(RedrawRequest request, boolean created) {

    /** 새로 저장한 요청 결과를 만든다. */
    static RedrawRequestCreationOutcome created(RedrawRequest request) {
        return new RedrawRequestCreationOutcome(request, true);
    }

    /** 잠금 후 발견한 기존 요청 결과를 만든다. */
    static RedrawRequestCreationOutcome existing(RedrawRequest request) {
        return new RedrawRequestCreationOutcome(request, false);
    }
}
