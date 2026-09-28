package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.dto.CommonEarnCommand;
import kr.co.cking.ticket.application.dto.EarnResult;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommonTicketEarnServiceImplTest {

    @Test
    void Creator_EARN에서_이미_사용한_requestId는_Redis_실행_전에_차단한다() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        TicketEarnRequestClaimService claimService = mock(TicketEarnRequestClaimService.class);
        when(claimService.claim(any(CommonEarnCommand.class))).thenReturn(TicketEarnRequestClaim.REQUEST_ID_CONFLICT);
        CommonTicketEarnService service = new CommonTicketEarnServiceImpl(
                redisTemplate, new DefaultRedisScript<List>(), "stream:common-ticket-earned:test", new ObjectMapper(), claimService);

        EarnResult result = service.earn(command());

        assertThat(result.code()).isEqualTo(EarnResultCode.REQUEST_ID_CONFLICT);
        verify(redisTemplate, never()).execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
    }

    private CommonEarnCommand command() {
        return new CommonEarnCommand(UUID.randomUUID(), 1L, "ATTENDANCE", 3L, "2026-09-16", 1L);
    }
}
