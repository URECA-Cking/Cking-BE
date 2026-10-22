package kr.co.cking.stream.application;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.stream.domain.DeadStreamMessage;
import kr.co.cking.stream.repository.DeadStreamMessageRepository;
import kr.co.cking.ticket.application.CommonEarnEventDispatcher;
import kr.co.cking.ticket.application.TicketEarnEventDispatcher;
import kr.co.cking.ticket.application.QuizRewardLedgerService;
import kr.co.cking.ticket.application.TicketEarnLedgerService;
import kr.co.cking.ticket.application.TicketSpendLedgerService;
import kr.co.cking.ticket.application.dto.SpendApplyResult;
import kr.co.cking.ticket.application.dto.SpendCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 운영자가 Dead Stream으로 이동한 메시지를 확인한 뒤 수동으로 재처리할 때 쓴다
 * (요구사항 v4 FR-14a / RTM FR-P2-020). 보존해 둔 원본 payload를 그대로
 * 재적용하고, 성공하면 RESOLVED로 표시한다 — {@link TicketEarnLedgerService#apply}·
 * {@link QuizRewardLedgerService#apply}·{@link TicketSpendLedgerService#apply}·
 * {@link CommonMissionEarnLedgerService#apply}가 requestId 기준으로 멱등하므로 중복 replay를 눌러도 안전하다.
 * EARN 메시지는 정상 소비와 같은 {@link TicketEarnEventDispatcher}로 적립 원인·버전을 나눈다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeadStreamReplayService {

    private final DeadStreamMessageRepository deadStreamMessageRepository;
    private final TicketEarnEventDispatcher earnEventDispatcher;
    private final TicketSpendLedgerService ticketSpendLedgerService;
    private final CommonEarnEventDispatcher commonEarnEventDispatcher;
    private final ObjectMapper objectMapper;

    /**
     * 이미 RESOLVED인 메시지는 다시 적용하지 않고 현재 상태를 그대로 반환한다(상태 기반 멱등). 행을 잠근 뒤 상태를
     * 검사하므로 동시 요청은 하나만 적용하고 나머지는 처음 처리한 결과를 그대로 받는다.
     */
    @Transactional
    public DeadStreamMessage replay(Long deadStreamMessageId, Long resolvedBy) {
        DeadStreamMessage message = deadStreamMessageRepository.findByIdForUpdate(deadStreamMessageId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (!message.isUnresolved()) {
            return message;
        }

        Map<String, String> fields = readPayload(message.getPayload());

        switch (message.getStreamType()) {
            // 정상 소비와 같은 적립 원인·버전 분기를 쓴다. Quiz 메시지를 Mission으로 바꾸지 않는다.
            case EARN -> earnEventDispatcher.apply(fields);
            case SPEND -> replaySpend(fields, message.getSourceStreamId());
            case COMMON_EARN -> commonEarnEventDispatcher.apply(fields);
            // PR #250 리뷰(문구): enum switch 문은 case 누락을 컴파일러가 잡아주지 않는다 -
            // DeadStreamType에 값이 추가되고 case를 빠뜨리면 아무것도 재적용하지 않은 채
            // 조용히 RESOLVED로 표시될 뻔했다. default를 명시해 그 경로를 막는다.
            default -> throw new IllegalStateException(
                    "지원하지 않는 DeadStreamType입니다: " + message.getStreamType());
        }

        message.resolve(resolvedBy, Instant.now());
        return message;
    }

    // 다시 차감된 메시지가 여기까지 오는 경우는 되돌리기가 5번 넘게 실패했을 때뿐이다. ACK가 이미 끝난 메시지라
    // 되돌리기와 ACK를 한 번에 묶을 수 없어, 되돌리지 않고 수동 보정이 필요하다고 남긴다(#673).
    private void replaySpend(Map<String, String> fields, String sourceStreamId) {
        SpendCommand command = SpendCommand.fromStreamFields(fields);
        if (ticketSpendLedgerService.apply(command, sourceStreamId) == SpendApplyResult.REDEDUCTED) {
            log.warn("Dead Stream replay: 다시 차감된 SPEND라 Redis 잔액이 DB보다 적습니다. resyncRedisToDb로 보정이 필요합니다. "
                    + "requestId={}, userId={}, sourceStreamId={}", command.requestId(), command.userId(), sourceStreamId);
        }
    }

    private Map<String, String> readPayload(String payload) {
        try {
            return objectMapper.readValue(payload, new TypeReference<Map<String, String>>() {
            });
        } catch (JacksonException e) {
            throw new IllegalStateException("Dead Stream payload를 역직렬화하지 못했습니다.", e);
        }
    }
}
