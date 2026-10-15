package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.dto.CommonEarnCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/** 공용 EARN의 기존 Mission 메시지와 평생 1회 메시지를 같은 소비 경계에서 분기한다. */
@Service
@RequiredArgsConstructor
public class CommonEarnEventDispatcher {
    private final CommonMissionEarnLedgerService missionLedgerService;
    private final CommonOnceEarnLedgerService onceLedgerService;

    /** Stream 원인에 맞는 DB 반영 서비스를 호출한다. */
    public void apply(Map<String, String> fields) {
        String sourceType = fields.get("sourceType");
        if (sourceType == null || "MISSION".equals(sourceType)) {
            missionLedgerService.apply(CommonEarnCommand.fromStreamFields(fields));
        } else if ("ONCE".equals(sourceType)) {
            onceLedgerService.apply(fields);
        } else {
            throw new IllegalStateException("지원하지 않는 공용 EARN 원인입니다: " + sourceType);
        }
    }
}
