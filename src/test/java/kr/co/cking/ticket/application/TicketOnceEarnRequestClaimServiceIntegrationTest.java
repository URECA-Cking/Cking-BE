package kr.co.cking.ticket.application;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.Mission;
import kr.co.cking.mission.MissionRepository;
import kr.co.cking.mission.domain.MissionType;
import kr.co.cking.ticket.application.dto.EarnCommand;
import kr.co.cking.ticket.application.dto.EarnRewardPolicy;
import kr.co.cking.ticket.domain.TicketOnceEarnRequestStatus;
import kr.co.cking.ticket.repository.TicketOnceEarnRequestRepository;
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
@Import({TicketOnceEarnRequestClaimService.class,
        TicketOnceEarnRequestClaimServiceIntegrationTest.FixedClockConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TicketOnceEarnRequestClaimServiceIntegrationTest {

    @Autowired
    private TicketOnceEarnRequestClaimService claimService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private CreatorRepository creatorRepository;

    @Autowired
    private MissionRepository missionRepository;

    @Autowired
    private TicketOnceEarnRequestRepository onceEarnRequestRepository;

    @Test
    void Redis_실행_전에_동일_평생_업무키를_durable하게_선점한다() {
        Member member = memberRepository.save(new Member("회원", "010-0000-0000", "member@example.com", MemberRole.USER));
        Member creatorMember = memberRepository.save(new Member("크리에이터", "010-1111-1111", "creator@example.com", MemberRole.USER));
        Creator creator = creatorRepository.save(new Creator(creatorMember.getMemberId(), "크리에이터"));
        Mission mission = missionRepository.save(new Mission(creator.getCreatorId(), MissionType.SHARE, 1, null, null));

        TicketOnceEarnRequestClaim first = claimService.claim(command(member, creator, mission, UUID.randomUUID()), "a".repeat(64));
        TicketOnceEarnRequestClaim second = claimService.claim(command(member, creator, mission, UUID.randomUUID()), "b".repeat(64));

        assertThat(first).isEqualTo(TicketOnceEarnRequestClaim.PENDING);
        assertThat(second).isEqualTo(TicketOnceEarnRequestClaim.DUPLICATE);
    }

    @Test
    void accept은_별도_트랜잭션에서_요청을_ACCEPTED로_확정한다() {
        EarnCommand command = newFixtureCommand();
        String fingerprint = command.computeFingerprint();
        claimService.claim(command, fingerprint);

        claimService.accept(command.requestId().toString());

        var request = onceEarnRequestRepository.findByRequestId(command.requestId().toString()).orElseThrow();
        assertThat(request.getStatus()).isEqualTo(TicketOnceEarnRequestStatus.ACCEPTED);
        assertThat(request.getAcceptedAt()).isEqualTo(Instant.parse("2026-09-16T00:00:00Z"));
        assertThat(claimService.claim(command, fingerprint)).isEqualTo(TicketOnceEarnRequestClaim.ACCEPTED);
        assertThat(claimService.find(command.requestId().toString(), fingerprint))
                .isEqualTo(TicketOnceEarnRequestClaim.ACCEPTED);
    }

    @Test
    void 동일_requestId에_다른_지문이면_충돌로_반환한다() {
        EarnCommand command = newFixtureCommand();
        claimService.claim(command, command.computeFingerprint());

        TicketOnceEarnRequestClaim result = claimService.claim(command, "a".repeat(64));

        assertThat(result).isEqualTo(TicketOnceEarnRequestClaim.REQUEST_ID_CONFLICT);
    }

    private EarnCommand newFixtureCommand() {
        Member member = memberRepository.save(new Member(
                "회원", "010-0000-0000", "member" + UUID.randomUUID() + "@example.com", MemberRole.USER));
        Member creatorMember = memberRepository.save(new Member(
                "크리에이터", "010-1111-1111", "creator" + UUID.randomUUID() + "@example.com", MemberRole.USER));
        Creator creator = creatorRepository.save(new Creator(creatorMember.getMemberId(), "크리에이터"));
        Mission mission = missionRepository.save(new Mission(creator.getCreatorId(), MissionType.SHARE, 1, null, null));
        return command(member, creator, mission, UUID.randomUUID());
    }

    private EarnCommand command(Member member, Creator creator, Mission mission, UUID requestId) {
        return new EarnCommand(requestId, member.getMemberId(), creator.getCreatorId(), MissionType.SHARE.name(),
                mission.getMissionId(), "2026-09-16", "share:" + creator.getCreatorId(), 1L, EarnRewardPolicy.ONCE);
    }

    @TestConfiguration
    static class FixedClockConfiguration {

        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-16T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
