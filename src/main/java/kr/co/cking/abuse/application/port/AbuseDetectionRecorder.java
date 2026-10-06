package kr.co.cking.abuse.application.port;

import kr.co.cking.abuse.domain.DetectionResult;

/** Rule 결과를 독립된 Detection 저장 흐름에 전달하는 application Port다. */
public interface AbuseDetectionRecorder {

    /** 저장 Transaction의 시작·flush·commit까지 완료하거나 실패를 호출자에게 전파한다. */
    void record(Long memberId, DetectionResult result);
}
