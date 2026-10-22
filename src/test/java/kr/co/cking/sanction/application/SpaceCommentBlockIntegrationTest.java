package kr.co.cking.sanction.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberNickname;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.notification.domain.Notification;
import kr.co.cking.notification.domain.NotificationType;
import kr.co.cking.notification.repository.NotificationRepository;
import kr.co.cking.sanction.application.dto.SpaceCommentBlockView;
import kr.co.cking.sanction.domain.MemberSanction;
import kr.co.cking.sanction.domain.SanctionErrorCode;
import kr.co.cking.sanction.domain.SanctionScope;
import kr.co.cking.sanction.domain.SanctionSource;
import kr.co.cking.sanction.repository.MemberSanctionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

/**
 * 실제 MySQL에서 크리에이터의 스페이스 댓글 차단(이슈 #579)을 확인한다. 차단이 그 스페이스에서만 작성을 막는지, 해제하면
 * 풀리는지, 같은 회원을 동시에 차단해도 유효한 차단이 하나만 남는지, DB CHECK가 스페이스 주인 없는 SPACE 행을 막는지 본다.
 */
@SpringBootTest
class SpaceCommentBlockIntegrationTest {

    @Autowired
    private SpaceCommentBlockService blockService;
    @Autowired
    private SanctionService sanctionService;
    @Autowired
    private SanctionQueryService queryService;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private CreatorRepository creatorRepository;
    @Autowired
    private MemberSanctionRepository sanctionRepository;
    @Autowired
    private NotificationRepository notificationRepository;

    private final List<Member> members = new ArrayList<>();
    private final List<Creator> creators = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        List<Long> memberIds = members.stream().map(Member::getMemberId).toList();
        // 제재 알림은 회원과 제재를 FK로 참조하고, 제재는 크리에이터와 회원을 FK로 참조하므로 이 순서로 지운다.
        notificationRepository.deleteAll(notificationRepository.findAll().stream()
                .filter(notification -> memberIds.contains(notification.getMemberId()))
                .toList());
        sanctionRepository.deleteAll(sanctionRepository.findAll().stream()
                .filter(sanction -> memberIds.contains(sanction.getMemberId())
                        || memberIds.contains(sanction.getCreatedBy())
                        || memberIds.contains(sanction.getReleasedBy()))
                .toList());
        creatorRepository.deleteAll(creators);
        memberRepository.deleteAll(members);
    }

    @Test
    void 차단하면_그_스페이스의_작성만_막히고_다른_스페이스는_쓸_수_있으며_해제하면_풀린다() {
        Creator blocker = creator();
        Creator other = creator();
        Member fan = member(MemberRole.USER);

        SpaceCommentBlockView block = blockService.block(blocker.getMemberId(), fan.getMemberId(), 7);

        assertThatThrownBy(() -> queryService.requireCommentWriteAllowed(fan.getMemberId(), blocker.getCreatorId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(SanctionErrorCode.SPACE_COMMENT_BLOCKED));
        // 다른 크리에이터의 스페이스에서는 계속 쓸 수 있다.
        queryService.requireCommentWriteAllowed(fan.getMemberId(), other.getCreatorId());

        blockService.release(blocker.getMemberId(), block.sanctionId());

        queryService.requireCommentWriteAllowed(fan.getMemberId(), blocker.getCreatorId());
        List<Notification> notifications = notificationRepository.findAll().stream()
                .filter(notification -> notification.getMemberId().equals(fan.getMemberId()))
                .toList();
        assertThat(notifications).extracting(Notification::getType).containsExactlyInAnyOrder(
                NotificationType.COMMENT_RESTRICTION_STARTED, NotificationType.COMMENT_RESTRICTION_RELEASED);
    }

    @Test
    void 같은_회원을_동시에_차단해도_유효한_차단은_하나만_남고_시작_알림도_한_번이다() throws Exception {
        Creator blocker = creator();
        Member fan = member(MemberRole.USER);
        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Long> first = executor.submit(() -> blockTogether(startTogether, blocker, fan, 3));
            Future<Long> second = executor.submit(() -> blockTogether(startTogether, blocker, fan, 5));

            // 늦게 잠근 쪽은 먼저 커밋된 차단을 읽고 새로 만들지 않으므로 두 요청이 같은 차단을 돌려준다.
            assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(second.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertThat(sanctionRepository.findActiveSpaceBlocks(blocker.getCreatorId(), fan.getMemberId(), Instant.now()))
                .hasSize(1);
        assertThat(notificationRepository.findAll().stream()
                .filter(notification -> notification.getMemberId().equals(fan.getMemberId()))
                .filter(notification -> notification.getType() == NotificationType.COMMENT_RESTRICTION_STARTED))
                .hasSize(1);
    }

    /**
     * 크리에이터 A가 B를, B가 A를 동시에 차단·해제해도 교착으로 한쪽이 롤백되지 않아야 한다. 제재 INSERT·해제 UPDATE의
     * created_by·released_by FK가 크리에이터 회원 행의 공유 잠금을 요구하므로, 대상 행만 잠그면 서로 상대 행을 기다리는
     * 순환 대기가 생긴다. 타이밍에 따라 드러나므로 여러 번 반복한다.
     */
    @Test
    void 두_크리에이터가_동시에_서로를_차단하고_해제해도_교착_없이_모두_성공한다() throws Exception {
        Creator a = creator();
        Creator b = creator();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                CyclicBarrier blockTogether = new CyclicBarrier(2);
                Future<Long> aBlocksB = executor.submit(() -> blockTogether(blockTogether, a, b.getMemberId()));
                Future<Long> bBlocksA = executor.submit(() -> blockTogether(blockTogether, b, a.getMemberId()));
                Long aBlockId = aBlocksB.get(30, TimeUnit.SECONDS);
                Long bBlockId = bBlocksA.get(30, TimeUnit.SECONDS);

                CyclicBarrier releaseTogether = new CyclicBarrier(2);
                Future<?> aReleases = executor.submit(() -> releaseTogether(releaseTogether, a, aBlockId));
                Future<?> bReleases = executor.submit(() -> releaseTogether(releaseTogether, b, bBlockId));
                aReleases.get(30, TimeUnit.SECONDS);
                bReleases.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(sanctionRepository.findActiveSpaceBlocks(a.getCreatorId(), b.getMemberId(), Instant.now())).isEmpty();
        assertThat(sanctionRepository.findActiveSpaceBlocks(b.getCreatorId(), a.getMemberId(), Instant.now())).isEmpty();
    }

    @Test
    void 관리자가_차단하면_그_스페이스의_작성만_막히고_관리자가_크리에이터가_건_차단도_해제할_수_있다() {
        Creator blocker = creator();
        Creator other = creator();
        Member fan = member(MemberRole.USER);
        Member admin = member(MemberRole.ADMIN);

        SpaceCommentBlockView byAdmin = blockService.blockByAdmin(admin.getMemberId(), blocker.getCreatorId(), fan.getMemberId(), 7);

        assertThat(byAdmin.createdBy()).isEqualTo(admin.getMemberId());
        assertThatThrownBy(() -> queryService.requireCommentWriteAllowed(fan.getMemberId(), blocker.getCreatorId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(SanctionErrorCode.SPACE_COMMENT_BLOCKED));
        queryService.requireCommentWriteAllowed(fan.getMemberId(), other.getCreatorId());
        // 같은 (크리에이터, 회원) 쌍에는 유효한 차단을 하나만 둔다. 크리에이터가 다시 걸어도 기존 차단이 돌아온다.
        assertThat(blockService.block(blocker.getMemberId(), fan.getMemberId(), 3).sanctionId())
                .isEqualTo(byAdmin.sanctionId());

        blockService.releaseByAdmin(admin.getMemberId(), byAdmin.sanctionId());

        queryService.requireCommentWriteAllowed(fan.getMemberId(), blocker.getCreatorId());
        assertThat(blockService.findBlocksByAdmin(
                admin.getMemberId(), blocker.getCreatorId(), fan.getMemberId(), false,
                org.springframework.data.domain.PageRequest.of(0, 10)).getContent())
                .singleElement().satisfies(found -> assertThat(found.active()).isFalse());
    }

    /**
     * 같은 관리자가 같은 대상에게 스페이스 차단과 플랫폼 수동 제한을 동시에 걸어도 교착 없이 모두 성공해야 한다. 플랫폼 경로는
     * 대상 행을 잠근 뒤 created_by FK로 관리자 행의 공유 잠금을 요구하므로, 스페이스 차단이 관리자 행을 먼저 쓰기 잠금으로 잡으면
     * (관리자 ID가 대상보다 작을 때) 서로의 잠금을 기다린다. 타이밍에 따라 드러나므로 새 대상으로 여러 번 반복한다.
     */
    @Test
    void 같은_관리자가_같은_대상에게_스페이스_차단과_플랫폼_제한을_동시에_걸어도_교착_없이_모두_성공한다() throws Exception {
        Member admin = member(MemberRole.ADMIN);
        Creator blocker = creator();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                // 대상이 항상 관리자보다 나중에 만들어져 ID가 크다(관리자 행을 먼저 잠그는 순서가 되는 조합).
                Member target = member(MemberRole.USER);
                CyclicBarrier startTogether = new CyclicBarrier(2);
                Future<Long> spaceBlock = executor.submit(() -> {
                    startTogether.await(10, TimeUnit.SECONDS);
                    return blockService.blockByAdmin(
                            admin.getMemberId(), blocker.getCreatorId(), target.getMemberId(), 1).sanctionId();
                });
                Future<Long> platform = executor.submit(() -> {
                    startTogether.await(10, TimeUnit.SECONDS);
                    return sanctionService.createManual(admin.getMemberId(), target.getMemberId(), 1, "반복 욕설")
                            .sanctionId();
                });

                assertThat(spaceBlock.get(30, TimeUnit.SECONDS)).isNotEqualTo(platform.get(30, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /** 관리자가 스페이스 차단을 해제하는 동안 같은 관리자가 같은 대상의 플랫폼 제한을 해제해도 교착이 나지 않는다. */
    @Test
    void 같은_관리자가_스페이스_차단과_플랫폼_제한을_동시에_해제해도_교착_없이_모두_성공한다() throws Exception {
        Member admin = member(MemberRole.ADMIN);
        Creator blocker = creator();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                Member target = member(MemberRole.USER);
                Long spaceBlockId = blockService.blockByAdmin(
                        admin.getMemberId(), blocker.getCreatorId(), target.getMemberId(), 1).sanctionId();
                Long platformId = sanctionService.createManual(admin.getMemberId(), target.getMemberId(), 1, "반복 욕설")
                        .sanctionId();

                CyclicBarrier startTogether = new CyclicBarrier(2);
                Future<?> releaseSpace = executor.submit(() -> {
                    startTogether.await(10, TimeUnit.SECONDS);
                    return blockService.releaseByAdmin(admin.getMemberId(), spaceBlockId);
                });
                Future<?> releasePlatform = executor.submit(() -> {
                    startTogether.await(10, TimeUnit.SECONDS);
                    return sanctionService.release(admin.getMemberId(), platformId);
                });

                releaseSpace.get(30, TimeUnit.SECONDS);
                releasePlatform.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 관리자는_스페이스_주인과_다른_관리자를_차단할_수_없다() {
        Creator blocker = creator();
        Member admin = member(MemberRole.ADMIN);
        Member otherAdmin = member(MemberRole.ADMIN);

        assertThatThrownBy(() -> blockService.blockByAdmin(
                admin.getMemberId(), blocker.getCreatorId(), blocker.getMemberId(), 1))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(SanctionErrorCode.CANNOT_BLOCK_SPACE_OWNER));
        assertThatThrownBy(() -> blockService.blockByAdmin(
                admin.getMemberId(), blocker.getCreatorId(), otherAdmin.getMemberId(), 1))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(SanctionErrorCode.CANNOT_BLOCK_ADMIN));
        assertThat(sanctionRepository.findAll().stream()
                .filter(sanction -> blocker.getCreatorId().equals(sanction.getSpaceCreatorId()))).isEmpty();
    }

    @Test
    void 관리자는_차단할_수_없고_차단도_알림도_저장되지_않는다() {
        Creator blocker = creator();
        Member admin = member(MemberRole.ADMIN);

        assertThatThrownBy(() -> blockService.block(blocker.getMemberId(), admin.getMemberId(), 1))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(SanctionErrorCode.CANNOT_BLOCK_ADMIN));

        assertThat(sanctionRepository.findAll().stream()
                .filter(sanction -> admin.getMemberId().equals(sanction.getMemberId()))).isEmpty();
        assertThat(notificationRepository.findAll().stream()
                .filter(notification -> admin.getMemberId().equals(notification.getMemberId()))).isEmpty();
    }

    /**
     * 댓글 숨김으로 생긴 1일 자동 제한(AUTOMATIC) 중에 30일 수동 차단을 걸어도 새로 만들어지고, 작성 가드와 시작 알림은 가장 늦게
     * 끝나는 쪽을 쓴다. 한쪽만 풀면 다른 제한이 남아 작성이 계속 막히고 해제 알림은 보내지 않으며, 둘 다 풀었을 때 한 번만 보낸다.
     */
    @Test
    void 자동_제한_중에_수동_차단을_걸면_가장_늦은_쪽이_적용되고_다른_제한이_남으면_해제_알림이_없다() {
        Creator space = creator();
        Member admin = member(MemberRole.ADMIN);
        Member fan = member(MemberRole.USER);
        sanctionService.recordCommentHide(
                fan.getMemberId(), causeCommentId(), space.getCreatorId(), admin.getMemberId(), Instant.now()).orElseThrow();
        MemberSanction automatic = spaceSanctionsOf(space, fan).stream()
                .filter(sanction -> sanction.getSource() == SanctionSource.AUTOMATIC)
                .findFirst().orElseThrow();

        SpaceCommentBlockView manual = blockService.blockByAdmin(
                admin.getMemberId(), space.getCreatorId(), fan.getMemberId(), 30);

        // 자동 제한이 있어도 수동 차단은 따로 만들어지고, 작성 가드는 더 늦게 끝나는 30일 쪽을 쓴다.
        // 응답의 시각은 메모리 값(나노초)이고 DB는 마이크로초로 저장하므로, 비교는 DB에서 다시 읽은 값으로 한다.
        Instant manualEndsAt = sanctionRepository.findById(manual.sanctionId()).orElseThrow().getEndsAt();
        assertThat(manual.sanctionId()).isNotEqualTo(automatic.getSanctionId());
        assertThat(sanctionRepository.findActiveSpaceBlockEndsAt(space.getCreatorId(), fan.getMemberId(), Instant.now()))
                .contains(manualEndsAt);
        Notification manualStarted = notificationRepository.findAll().stream()
                .filter(notification -> manual.sanctionId().equals(notification.getSanctionId()))
                .filter(notification -> notification.getType() == NotificationType.COMMENT_RESTRICTION_STARTED)
                .findFirst().orElseThrow();
        assertThat(manualStarted.getBody()).contains(untilText(manualEndsAt) + "까지");

        // 수동 차단만 풀면 자동 제한이 남아 있어 여전히 막히고 해제 알림은 없다.
        blockService.releaseByAdmin(admin.getMemberId(), manual.sanctionId());
        assertThatThrownBy(() -> queryService.requireCommentWriteAllowed(fan.getMemberId(), space.getCreatorId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(SanctionErrorCode.SPACE_COMMENT_BLOCKED));
        assertThat(releasedNotificationsOf(fan)).isEmpty();

        // 자동 제한까지 풀면 작성이 풀리고 해제 알림이 한 번 간다.
        blockService.releaseByAdmin(admin.getMemberId(), automatic.getSanctionId());
        queryService.requireCommentWriteAllowed(fan.getMemberId(), space.getCreatorId());
        assertThat(releasedNotificationsOf(fan)).hasSize(1);
    }

    /** 관리자의 댓글 숨김으로 생긴 자동 제한은 중재 기록이라 크리에이터는 풀 수 없고 크리에이터 목록에도 나오지 않는다. */
    @Test
    void 크리에이터는_숨김으로_생긴_자동_제한을_풀거나_볼_수_없고_관리자는_본다() {
        Creator space = creator();
        Member admin = member(MemberRole.ADMIN);
        Member fan = member(MemberRole.USER);
        sanctionService.recordCommentHide(
                fan.getMemberId(), causeCommentId(), space.getCreatorId(), admin.getMemberId(), Instant.now()).orElseThrow();
        MemberSanction automatic = spaceSanctionsOf(space, fan).get(0);

        assertThatThrownBy(() -> blockService.release(space.getMemberId(), automatic.getSanctionId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
        assertThat(blockService.findBlocks(space.getMemberId(), false, PageRequest.of(0, 20)).getContent()).isEmpty();
        // 풀리지 않았으므로 작성은 계속 막혀 있고, 관리자는 목록에서 보고 풀 수 있다.
        assertThatThrownBy(() -> queryService.requireCommentWriteAllowed(fan.getMemberId(), space.getCreatorId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(SanctionErrorCode.SPACE_COMMENT_BLOCKED));
        assertThat(blockService.findBlocksByAdmin(
                admin.getMemberId(), space.getCreatorId(), fan.getMemberId(), false, PageRequest.of(0, 20)).getContent())
                .extracting(SpaceCommentBlockView::sanctionId).contains(automatic.getSanctionId());
        blockService.releaseByAdmin(admin.getMemberId(), automatic.getSanctionId());
        queryService.requireCommentWriteAllowed(fan.getMemberId(), space.getCreatorId());
    }

    @Test
    void 스페이스_범위_제재에_스페이스_주인이_없으면_DB가_거부한다() {
        Member fan = member(MemberRole.USER);
        Member admin = member(MemberRole.ADMIN);
        MemberSanction platform = MemberSanction.manual(
                fan.getMemberId(), admin.getMemberId(), "사유", Instant.now(), java.time.Duration.ofDays(1));
        org.springframework.test.util.ReflectionTestUtils.setField(platform, "scope", SanctionScope.SPACE);

        assertThatThrownBy(() -> sanctionRepository.saveAndFlush(platform))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** 원인 댓글 ID는 전체 테이블에서 유일해야 하므로 겹치지 않는 큰 값을 만든다(댓글이 실제로 있을 필요는 없다). */
    private Long causeCommentId() {
        return 9_100_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L);
    }

    private List<MemberSanction> spaceSanctionsOf(Creator space, Member fan) {
        return sanctionRepository.findAll().stream()
                .filter(sanction -> fan.getMemberId().equals(sanction.getMemberId()))
                .filter(sanction -> space.getCreatorId().equals(sanction.getSpaceCreatorId()))
                .toList();
    }

    private List<Notification> releasedNotificationsOf(Member fan) {
        return notificationRepository.findAll().stream()
                .filter(notification -> fan.getMemberId().equals(notification.getMemberId()))
                .filter(notification -> notification.getType() == NotificationType.COMMENT_RESTRICTION_RELEASED)
                .toList();
    }

    /** 알림은 초 이하를 버리지 않고 다음 분으로 올려 표기하므로 같은 규칙으로 기대 문구를 만든다. */
    private String untilText(Instant endsAt) {
        Instant ceiled = endsAt.truncatedTo(ChronoUnit.MINUTES).equals(endsAt)
                ? endsAt : endsAt.truncatedTo(ChronoUnit.MINUTES).plus(1, ChronoUnit.MINUTES);
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC).format(ceiled);
    }

    private Long blockTogether(CyclicBarrier startTogether, Creator blocker, Long targetMemberId) throws Exception {
        startTogether.await(10, TimeUnit.SECONDS);
        return blockService.block(blocker.getMemberId(), targetMemberId, 1).sanctionId();
    }

    private Void releaseTogether(CyclicBarrier startTogether, Creator blocker, Long sanctionId) throws Exception {
        startTogether.await(10, TimeUnit.SECONDS);
        blockService.release(blocker.getMemberId(), sanctionId);
        return null;
    }

    private Long blockTogether(CyclicBarrier startTogether, Creator blocker, Member fan, int days) throws Exception {
        startTogether.await(10, TimeUnit.SECONDS);
        return blockService.block(blocker.getMemberId(), fan.getMemberId(), days).sanctionId();
    }

    private Creator creator() {
        Member owner = member(MemberRole.USER);
        Creator creator = creatorRepository.saveAndFlush(new Creator(owner.getMemberId(), "creator-" + suffix()));
        creators.add(creator);
        return creator;
    }

    private Member member(MemberRole role) {
        String suffix = suffix();
        Member member = new Member("space-block-" + suffix, null, null, role);
        member.setInitialNickname(MemberNickname.of("block" + suffix));
        member = memberRepository.saveAndFlush(member);
        members.add(member);
        return member;
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
