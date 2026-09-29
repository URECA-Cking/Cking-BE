package kr.co.cking.mission;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.application.CreatorSpaceProfileService;
import kr.co.cking.creator.application.dto.CreatorSpaceView;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.domain.CreatorSpaceTemplate;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.application.CreatorSpaceShareMissionCompletionService;
import kr.co.cking.mission.application.dto.MissionCompleteCommand;
import kr.co.cking.mission.application.dto.MissionCompleteOutcome;
import kr.co.cking.mission.domain.MissionType;
import kr.co.cking.ticket.application.dto.EarnResultCode;
import kr.co.cking.ticket.application.config.TicketRedisKeys;
import kr.co.cking.ticket.domain.UserTicketBalance;
import kr.co.cking.ticket.repository.UserTicketBalanceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Creator Space 공개 slug 조회부터 SHARE 보상과 EARN Consumer DB 반영까지의 흐름을 검증한다.
 * 로컬 MySQL·Redis와 EARN Stream Consumer가 실행 중이어야 한다.
 */
@SpringBootTest
class CreatorSpaceShareE2EIntegrationTest {

    private static final long AWAIT_TIMEOUT_MILLIS = 8000L;

    @Autowired
    private CreatorSpaceProfileService creatorSpaceProfileService;
    @Autowired
    private CreatorSpaceShareMissionCompletionService shareMissionCompletionService;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private CreatorRepository creatorRepository;
    @Autowired
    private CreatorSpaceRepository creatorSpaceRepository;
    @Autowired
    private MissionRepository missionRepository;
    @Autowired
    private MissionCompletionRepository missionCompletionRepository;
    @Autowired
    private UserTicketBalanceRepository userTicketBalanceRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redisTemplate;

    private final List<Long> memberIds = new ArrayList<>();
    private final Set<String> redisKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> requestIds = ConcurrentHashMap.newKeySet();

    /** 테스트가 만든 EARN·미션·Creator 데이터를 외래 키 역순으로 정리한다. */
    @AfterEach
    void cleanUp() {
        redisTemplate.delete(redisKeys);
        for (Long memberId : memberIds) {
            jdbcTemplate.update("DELETE FROM ticket_ledger WHERE member_id = ?", memberId);
            jdbcTemplate.update("DELETE FROM user_ticket_balance WHERE member_id = ?", memberId);
            jdbcTemplate.update("DELETE FROM mission_completion WHERE member_id = ?", memberId);
            jdbcTemplate.update("DELETE FROM ticket_earn_request WHERE request_id IN "
                    + "(SELECT request_id FROM ticket_once_earn_request WHERE member_id = ?)", memberId);
            jdbcTemplate.update("DELETE FROM ticket_once_earn_request WHERE member_id = ?", memberId);
        }
        for (Long memberId : memberIds) {
            jdbcTemplate.update("DELETE FROM creator_space_slug_reservation WHERE creator_id IN "
                    + "(SELECT creator_id FROM creator WHERE member_id = ?)", memberId);
            jdbcTemplate.update("DELETE FROM creator_space WHERE creator_id IN "
                    + "(SELECT creator_id FROM creator WHERE member_id = ?)", memberId);
            jdbcTemplate.update("DELETE FROM mission WHERE creator_id IN "
                    + "(SELECT creator_id FROM creator WHERE member_id = ?)", memberId);
            jdbcTemplate.update("DELETE FROM creator WHERE member_id = ?", memberId);
        }
        for (Long memberId : memberIds) {
            jdbcTemplate.update("DELETE FROM member WHERE member_id = ?", memberId);
        }
        requestIds.forEach(requestId -> jdbcTemplate.update(
                "DELETE FROM ticket_earn_request WHERE request_id = ?", requestId));
    }

    /** 최초 공유·재공유·동일 요청 재전송과 다른 Creator 공유의 보상 경계를 한 흐름으로 검증한다. */
    @Test
    void 공개_slug에서_해석한_creatorId로_SHARE를_완료하면_Creator별_평생_한번만_적립된다() {
        Member viewer = createMember("공유참여자");
        CreatorFixture first = createCreator("첫크리에이터", "first-share-space");
        CreatorFixture second = createCreator("둘째크리에이터", "second-share-space");

        CreatorSpaceView resolved = creatorSpaceProfileService.findBySlug(first.slug());
        UUID firstRequestId = UUID.randomUUID();
        MissionCompleteOutcome firstOutcome = complete(resolved.space().getCreatorId(), viewer.getMemberId(), firstRequestId);
        assertThat(firstOutcome.code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);
        assertThat(awaitBalance(viewer.getMemberId(), first.creatorId(), 1L)).isEqualTo(1L);

        assertThatThrownBy(() -> complete(first.creatorId(), viewer.getMemberId(), UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(kr.co.cking.mission.domain.MissionErrorCode.DUPLICATE_MISSION);
        assertThat(complete(first.creatorId(), viewer.getMemberId(), firstRequestId).code())
                .isEqualTo(EarnResultCode.ALREADY_PROCESSED);
        assertThat(currentBalance(viewer.getMemberId(), first.creatorId())).isEqualTo(1L);

        assertThat(complete(second.creatorId(), viewer.getMemberId(), UUID.randomUUID()).code())
                .isEqualTo(EarnResultCode.EARN_ACCEPTED);
        assertThat(awaitBalance(viewer.getMemberId(), second.creatorId(), 1L)).isEqualTo(1L);
    }

    /** 같은 SHARE 미션의 세 동시 요청이 durable Business Key와 Redis Guard에서 한 번만 승인되는지 검증한다. */
    @Test
    void 같은_SHARE_미션을_동시에_요청해도_한번만_적립된다() throws Exception {
        Member viewer = createMember("동시공유참여자");
        CreatorFixture creator = createCreator("동시공유크리에이터", "concurrent-share-space");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<String> first = executor.submit(() -> completeAfterStart(start, creator.creatorId(), viewer.getMemberId()));
            Future<String> second = executor.submit(() -> completeAfterStart(start, creator.creatorId(), viewer.getMemberId()));
            Future<String> third = executor.submit(() -> completeAfterStart(start, creator.creatorId(), viewer.getMemberId()));
            start.countDown();

            List<String> results = List.of(first.get(), second.get(), third.get());
            assertThat(results).containsExactlyInAnyOrder(
                    EarnResultCode.EARN_ACCEPTED.name(),
                    kr.co.cking.mission.domain.MissionErrorCode.DUPLICATE_MISSION.code(),
                    kr.co.cking.mission.domain.MissionErrorCode.DUPLICATE_MISSION.code());
            assertThat(awaitBalance(viewer.getMemberId(), creator.creatorId(), 1L)).isEqualTo(1L);
            assertThat(currentBalance(viewer.getMemberId(), creator.creatorId())).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    /** slug 변경 뒤에는 새 링크만 Space를 열고, 기존 SHARE 완료 이력은 변하지 않는 Creator ID를 유지하는지 검증한다. */
    @Test
    void slug_변경_후_새_slug로_공유해도_기존_미션_기록은_같은_creatorId를_가리킨다() {
        Member viewer = createMember("slug변경공유참여자");
        CreatorFixture creator = createCreator("slug변경크리에이터", "before-share-slug");
        UUID requestId = UUID.randomUUID();

        complete(creator.creatorId(), viewer.getMemberId(), requestId);
        awaitBalance(viewer.getMemberId(), creator.creatorId(), 1L);
        CreatorSpaceView changed = creatorSpaceProfileService.changeSlug(creator.ownerMemberId(), "after-share-slug");

        CreatorSpaceView resolved = creatorSpaceProfileService.findBySlug(changed.space().getSlug());
        assertThat(resolved.space().getCreatorId()).isEqualTo(creator.creatorId());
        assertThatThrownBy(() -> creatorSpaceProfileService.findBySlug(creator.slug()))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
        assertThat(missionCompletionRepository.findByRequestId(requestId.toString()).orElseThrow().getCreatorId())
                .isEqualTo(creator.creatorId());

        Member newViewer = createMember("새slug공유참여자");
        MissionCompleteOutcome outcome = complete(resolved.space().getCreatorId(), newViewer.getMemberId(), UUID.randomUUID());

        assertThat(outcome.code()).isEqualTo(EarnResultCode.EARN_ACCEPTED);
        assertThat(awaitBalance(newViewer.getMemberId(), creator.creatorId(), 1L)).isEqualTo(1L);
    }

    /** 공개 조회와 SHARE 완료에 필요한 Creator·Space·기본 SHARE 미션을 직접 만든다. */
    private CreatorFixture createCreator(String name, String slug) {
        Member owner = createMember(name + "회원");
        Creator creator = creatorRepository.saveAndFlush(new Creator(owner.getMemberId(), name));
        CreatorSpaceTemplate template = new CreatorSpaceTemplate(
                owner.getMemberId(), "소개", "https://img/profile.png", "https://img/banner.png", "creator-{creatorId}"
        );
        creatorSpaceRepository.saveAndFlush(CreatorSpace.fromTemplate(creator.getCreatorId(), template, slug));
        Mission mission = missionRepository.saveAndFlush(
                new Mission(creator.getCreatorId(), MissionType.SHARE, 1, null, null));
        return new CreatorFixture(owner.getMemberId(), creator.getCreatorId(), mission.getMissionId(), slug);
    }

    /** E2E 보상 요청을 만들고 결과를 반환한다. */
    private MissionCompleteOutcome complete(Long creatorId, Long memberId, UUID requestId) {
        requestIds.add(requestId.toString());
        redisKeys.add(TicketRedisKeys.idemMissionOnce(requestId.toString()));
        redisKeys.add(TicketRedisKeys.earnGuard(memberId, MissionType.SHARE.name(), creatorId, "once"));
        redisKeys.add(TicketRedisKeys.balance(creatorId, memberId));
        return shareMissionCompletionService.complete(creatorId, new MissionCompleteCommand(memberId, requestId));
    }

    /** 동시 실행을 시작 신호까지 대기시킨 후 보상 결과 또는 업무 오류 코드를 반환한다. */
    private String completeAfterStart(CountDownLatch start, Long creatorId, Long memberId) throws InterruptedException {
        start.await();
        try {
            return complete(creatorId, memberId, UUID.randomUUID()).code().name();
        } catch (BusinessException exception) {
            return exception.getErrorCode().code();
        }
    }

    /** EARN Consumer가 Creator 전용 잔액을 기대값 이상으로 반영할 때까지 기다린다. */
    private long awaitBalance(Long memberId, Long creatorId, long expected) {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            long balance = currentBalance(memberId, creatorId);
            if (balance >= expected) {
                return balance;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }
        throw new AssertionError("SHARE EARN이 제한 시간 내 반영되지 않았습니다. memberId=" + memberId);
    }

    /** 현재 Creator 전용 응모권 잔액을 조회한다. */
    private long currentBalance(Long memberId, Long creatorId) {
        return userTicketBalanceRepository.findByMemberIdAndCreatorId(memberId, creatorId)
                .map(UserTicketBalance::getBalance)
                .orElse(0L);
    }

    /** 테스트에서 사용할 USER Member를 만들고 정리 대상에 기록한다. */
    private Member createMember(String name) {
        Member member = memberRepository.saveAndFlush(new Member(name, null, null, MemberRole.USER));
        memberIds.add(member.getMemberId());
        return member;
    }

    /** Creator Space와 SHARE 미션의 변하지 않는 식별자를 함께 보관한다. */
    private record CreatorFixture(Long ownerMemberId, Long creatorId, Long missionId, String slug) {
    }
}
