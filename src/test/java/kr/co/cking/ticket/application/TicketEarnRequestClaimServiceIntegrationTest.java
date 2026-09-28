package kr.co.cking.ticket.application;

import kr.co.cking.ticket.application.dto.EarnCommand;
import kr.co.cking.ticket.application.dto.EarnRewardPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TicketEarnRequestClaimService.class,
        TicketEarnRequestClaimServiceIntegrationTest.FixedClockConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TicketEarnRequestClaimServiceIntegrationTest {

    @Autowired
    private TicketEarnRequestClaimService claimService;

    @Test
    void DAILY와_ONCE는_같은_requestId를_공유할_수_없다() {
        UUID requestId = UUID.randomUUID();
        EarnCommand daily = command(requestId, EarnRewardPolicy.DAILY);
        EarnCommand once = command(requestId, EarnRewardPolicy.ONCE);

        assertThat(claimService.claim(daily)).isEqualTo(TicketEarnRequestClaim.PENDING);
        assertThat(claimService.claim(once)).isEqualTo(TicketEarnRequestClaim.REQUEST_ID_CONFLICT);
        assertThat(claimService.find(once)).isEqualTo(TicketEarnRequestClaim.REQUEST_ID_CONFLICT);
    }

    private EarnCommand command(UUID requestId, EarnRewardPolicy rewardPolicy) {
        return new EarnCommand(requestId, 1L, 2L, "LIKE", 3L, "2026-09-16", "like:2", 1L, rewardPolicy);
    }

    @TestConfiguration
    static class FixedClockConfiguration {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-16T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
