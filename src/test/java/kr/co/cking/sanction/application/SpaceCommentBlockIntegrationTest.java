package kr.co.cking.sanction.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kr.co.cking.common.exception.BusinessException;
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
import kr.co.cking.sanction.repository.MemberSanctionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 실제 MySQL에서 크리에이터의 스페이스 댓글 차단(이슈 #579)을 확인한다. 차단이 그 스페이스에서만 작성을 막는지, 해제하면
 * 풀리는지, 같은 회원을 동시에 차단해도 유효한 차단이 하나만 남는지, DB CHECK가 스페이스 주인 없는 SPACE 행을 막는지 본다.
 */
@SpringBootTest
class SpaceCommentBlockIntegrationTest {

    @Autowired
    private SpaceCommentBlockService blockService;
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
