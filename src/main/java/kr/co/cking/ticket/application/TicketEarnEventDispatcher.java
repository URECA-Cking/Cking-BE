package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.dto.EarnCommand;
import kr.co.cking.ticket.application.dto.QuizEarnEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * {@code stream:ticket-earned} 메시지를 적립 원인(source)과 payload 버전으로 나눠 DB 반영 서비스에 넘긴다(이슈 #533).
 * 정상 소비({@code EarnStreamListener}·PEL 재처리)와 Dead Stream replay가 같은 분기를 쓴다.
 *
 * <ul>
 *   <li>{@code sourceType}·{@code payloadVersion}이 모두 없는 메시지: 기존 Mission 메시지로 처리한다</li>
 *   <li>{@code sourceType=MISSION}: 지원하는 버전만 Mission으로 처리한다</li>
 *   <li>{@code sourceType=QUIZ}: Quiz v1만 Quiz 전용 검증·반영으로 처리한다. Mission 경로로 보내지 않는다</li>
 *   <li>그 밖의 source·version, 또는 버전만 있고 source가 없는 메시지: DB에 반영하지 않고
 *       {@link IllegalArgumentException}으로 거부한다. Mission으로 바꾸지 않는다</li>
 * </ul>
 * 거부된 메시지는 호출자가 XACK하지 않으므로 기존 PEL·Dead Stream 정책을 그대로 따른다.
 */
@Component
@RequiredArgsConstructor
public class TicketEarnEventDispatcher {

    static final String MISSION_SOURCE = "MISSION";
    /** 명시적 MISSION 메시지가 지원하는 payload 버전. 필드 구성은 기존 Mission 메시지와 같다. */
    static final Set<String> MISSION_PAYLOAD_VERSIONS = Set.of("1");

    private final TicketEarnLedgerService missionLedgerService;
    private final QuizRewardLedgerService quizRewardLedgerService;

    public void apply(Map<String, String> fields) {
        String sourceType = fields.get("sourceType");
        String payloadVersion = fields.get("payloadVersion");
        if (sourceType == null) {
            if (payloadVersion != null) {
                throw new IllegalArgumentException("sourceType 없이 payloadVersion만 있는 EARN 메시지입니다.");
            }
            missionLedgerService.apply(EarnCommand.fromStreamFields(fields));
            return;
        }
        switch (sourceType) {
            case MISSION_SOURCE -> {
                if (payloadVersion == null || !MISSION_PAYLOAD_VERSIONS.contains(payloadVersion)) {
                    throw new IllegalArgumentException("지원하지 않는 Mission EARN 메시지 버전입니다: " + payloadVersion);
                }
                missionLedgerService.apply(EarnCommand.fromStreamFields(fields));
            }
            case QuizEarnEvent.SOURCE_TYPE -> quizRewardLedgerService.apply(QuizEarnEvent.fromStreamFields(fields));
            default -> throw new IllegalArgumentException("지원하지 않는 EARN 적립 원인입니다: " + sourceType);
        }
    }
}
