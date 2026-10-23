package kr.co.cking.creator.application;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorApplication;
import kr.co.cking.creator.domain.CreatorApplicationStatus;
import kr.co.cking.creator.domain.CreatorSpace;
import kr.co.cking.creator.repository.CreatorApplicationRepository;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSpaceRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Creator 승인과 Space 생성의 트랜잭션 경계를 검증한다. */
@SpringBootTest
class CreatorApprovalSpaceCreationIntegrationTest {

    @Autowired
    private CreatorApplicationService creatorApplicationService;

    @Autowired
    private CreatorSpaceService creatorSpaceService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private CreatorRepository creatorRepository;

    @Autowired
    private CreatorApplicationRepository creatorApplicationRepository;

    @Autowired
    private CreatorSpaceRepository creatorSpaceRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> memberIds = new ArrayList<>();
    private final List<Long> applicationIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        memberIds.forEach(memberId -> {
            jdbcTemplate.update(
                    "DELETE FROM creator_space WHERE creator_id IN (SELECT creator_id FROM creator WHERE member_id = ?)",
                    memberId
            );
            jdbcTemplate.update(
                    "DELETE FROM mission WHERE creator_id IN (SELECT creator_id FROM creator WHERE member_id = ?)",
                    memberId
            );
            jdbcTemplate.update("DELETE FROM creator WHERE member_id = ?", memberId);
        });
        applicationIds.forEach(applicationId -> jdbcTemplate.update(
                "DELETE FROM creator_application WHERE id = ?", applicationId));
        memberIds.forEach(memberId -> jdbcTemplate.update("DELETE FROM member WHERE member_id = ?", memberId));
    }

    @Test
    void 템플릿_없이_승인해도_기본값으로_스페이스를_생성한다() {
        Member admin = createMember("스페이스관리자", MemberRole.ADMIN);
        Member applicant = createMember("스페이스신청자", MemberRole.USER);
        CreatorApplication application = createApplication(applicant.getMemberId());

        creatorApplicationService.approve(admin.getMemberId(), application.getId());

        Creator creator = creatorRepository.findByMemberId(applicant.getMemberId()).orElseThrow();
        CreatorSpace space = creatorSpaceRepository.findByCreatorId(creator.getCreatorId()).orElseThrow();
        assertThat(space.getIntroText()).isEqualTo(CreatorSpace.DEFAULT_INTRO_TEXT);
        assertThat(space.getProfileImageUrl()).isNull();
        assertThat(space.getDefaultProfileImageUrl()).isNull();
        assertThat(space.getBannerImageUrl()).isNull();
        assertThat(space.getDefaultBannerImageUrl()).isNull();
        assertThat(space.getSlug()).isEqualTo("creator-" + creator.getCreatorId());
    }

    @Test
    void 여러_크리에이터를_승인해도_slug가_충돌하지_않는다() {
        Member admin = createMember("복수승인관리자", MemberRole.ADMIN);
        Member firstApplicant = createMember("복수승인신청자1", MemberRole.USER);
        Member secondApplicant = createMember("복수승인신청자2", MemberRole.USER);
        CreatorApplication firstApplication = createApplication(firstApplicant.getMemberId());
        CreatorApplication secondApplication = createApplication(secondApplicant.getMemberId());

        creatorApplicationService.approve(admin.getMemberId(), firstApplication.getId());
        creatorApplicationService.approve(admin.getMemberId(), secondApplication.getId());

        Creator firstCreator = creatorRepository.findByMemberId(firstApplicant.getMemberId()).orElseThrow();
        Creator secondCreator = creatorRepository.findByMemberId(secondApplicant.getMemberId()).orElseThrow();
        CreatorSpace firstSpace = creatorSpaceRepository.findByCreatorId(firstCreator.getCreatorId()).orElseThrow();
        CreatorSpace secondSpace = creatorSpaceRepository.findByCreatorId(secondCreator.getCreatorId()).orElseThrow();
        assertThat(firstSpace.getSlug()).isNotEqualTo(secondSpace.getSlug());
    }

    @Test
    void 동시_승인은_같은_레거시_이름이어도_접미사_활동명으로_모두_성공한다() throws Exception {
        Member admin = createMember("동시승인관리자", MemberRole.ADMIN);
        String sharedLegacyName = "a" + Long.toString(System.nanoTime(), 36);
        Member firstApplicant = createMember(sharedLegacyName, MemberRole.USER);
        Member secondApplicant = createMember(sharedLegacyName, MemberRole.USER);
        CreatorApplication firstApplication = createApplication(firstApplicant.getMemberId());
        CreatorApplication secondApplication = createApplication(secondApplicant.getMemberId());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> approveAfterStart(start, admin.getMemberId(), firstApplication.getId()));
            Future<?> second = executor.submit(() -> approveAfterStart(start, admin.getMemberId(), secondApplication.getId()));

            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);

            assertThat(List.of(
                    creatorRepository.findByMemberId(firstApplicant.getMemberId()).orElseThrow().getActivityName(),
                    creatorRepository.findByMemberId(secondApplicant.getMemberId()).orElseThrow().getActivityName()))
                    .containsExactlyInAnyOrder(sharedLegacyName, sharedLegacyName + "-2");
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void 동일_크리에이터에_대해_스페이스가_두_번_생성되지_않는다() {
        Member admin = createMember("멱등관리자", MemberRole.ADMIN);
        Member creatorMember = createMember("멱등크리에이터", MemberRole.USER);
        Creator creator = creatorRepository.saveAndFlush(new Creator(creatorMember.getMemberId(), "멱등크리에이터"));

        CreatorSpace first = creatorSpaceService.createIfAbsent(creator.getCreatorId());
        CreatorSpace second = creatorSpaceService.createIfAbsent(creator.getCreatorId());

        assertThat(second.getSpaceId()).isEqualTo(first.getSpaceId());
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM creator_space WHERE creator_id = ?", Long.class, creator.getCreatorId());
        assertThat(count).isEqualTo(1L);
    }

    private Member createMember(String name, MemberRole role) {
        Member member = memberRepository.saveAndFlush(new Member(name, null, null, role));
        memberIds.add(member.getMemberId());
        return member;
    }

    private CreatorApplication createApplication(Long applicantId) {
        CreatorApplication application = creatorApplicationRepository.saveAndFlush(new CreatorApplication(applicantId));
        applicationIds.add(application.getId());
        return application;
    }

    private void approveAfterStart(CountDownLatch start, Long adminId, Long applicationId) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("동시 승인 시작 대기 시간이 초과되었습니다.");
            }
            creatorApplicationService.approve(adminId, applicationId);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
