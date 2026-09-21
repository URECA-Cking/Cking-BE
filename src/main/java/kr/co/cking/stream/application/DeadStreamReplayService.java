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
import kr.co.cking.ticket.application.TicketEarnLedgerService;
import kr.co.cking.ticket.application.TicketSpendLedgerService;
import kr.co.cking.ticket.application.dto.EarnCommand;
import kr.co.cking.ticket.application.dto.SpendCommand;
import lombok.RequiredArgsConstructor;

/**
 * 운영자가 Dead Stream으로 이동한 메시지를 확인한 뒤 수동으로 재처리할 때 쓴다
 * (요구사항 v4 FR-14a / RTM FR-P2-020). 보존해 둔 원본 payload를 그대로
 * 재적용하고, 성공하면 RESOLVED로 표시한다 — {@link TicketEarnLedgerService#apply}·
 * {@link TicketSpendLedgerService#apply}가 requestId 기준으로 멱등하므로 중복
 * replay를 눌러도 안전하다.
 */
@Service
@RequiredArgsConstructor
public class DeadStreamReplayService {

    private final DeadStreamMessageRepository deadStreamMessageRepository;
    private final TicketEarnLedgerService ticketEarnLedgerService;
    private final TicketSpendLedgerService ticketSpendLedgerService;
    private final ObjectMapper objectMapper;

    /** 이미 RESOLVED인 메시지는 다시 적용하지 않고 현재 상태를 그대로 반환한다(상태 기반 멱등). */
    @Transactional
    public DeadStreamMessage replay(Long deadStreamMessageId, Long resolvedBy) {
        DeadStreamMessage message = deadStreamMessageRepository.findById(deadStreamMessageId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (!message.isUnresolved()) {
            return message;
        }

        Map<String, String> fields = readPayload(message.getPayload());

        switch (message.getStreamType()) {
            case EARN -> ticketEarnLedgerService.apply(EarnCommand.fromStreamFields(fields));
            case SPEND -> ticketSpendLedgerService.apply(SpendCommand.fromStreamFields(fields));
        }

        message.resolve(resolvedBy, Instant.now());
        return message;
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
