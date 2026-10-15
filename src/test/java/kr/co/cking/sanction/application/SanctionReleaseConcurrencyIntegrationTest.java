package kr.co.cking.sanction.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberNickname;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.notification.domain.Notification;
import kr.co.cking.notification.domain.NotificationType;
import kr.co.cking.notification.repository.NotificationRepository;
import kr.co.cking.sanction.repository.MemberSanctionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 실제 MySQL에서 같은 회원의 겹친 두 제한을 동시에 해제해도 해제 알림이 정확히 한 번 만들어지는지 확인한다(이슈 #572).
 * 해제는 제재 행 → 회원 행 순서로 잠그고 {@code READ_COMMITTED}로 실행하므로, 늦게 잠근 쪽이 먼저 커밋된 해제를 읽어
 * 마지막 제한을 푼 쪽만 알린다. 잠금이나 격리 수준이 빠지면 둘 다 상대가 아직 유효하다고 읽어 알림이 0건이 된다.
 */
@SpringBootTest
class SanctionReleaseConcurrencyIntegrationTest {

    /** 한 번의 경합만으로는 잘못된 순서가 우연히 통과할 수 있어 새 제재로 여러 번 반복한다. */
    private static final int ROUNDS = 5;

    @Autowired
    private SanctionService sanctionService;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private MemberSanctionRepository sanctionRepository;
    @Autowired
    private NotificationRepository notificationRepository;

    private final List<Member> members = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        List<Long> memberIds = members.stream().map(Member::getMemberId).toList();
        // 제재 알림은 회원과 제재를 FK로 참조하므로 제재보다 먼저 지운다.
        notificationRepository.deleteAll(notificationRepository.findAll().stream()
                .filter(notification -> memberIds.contains(notification.getMemberId()))
                .toList());
        sanctionRepository.deleteAll(sanctionRepository.findAll().stream()
                .filter(sanction -> memberIds.contains(sanction.getMemberId())
                        || memberIds.contains(sanction.getCreatedBy())
                        || memberIds.contains(sanction.getReleasedBy()))
                .toList());
        memberRepository.deleteAll(members);
    }

    @Test
    void 겹친_두_제한을_동시에_해제해도_해제_알림은_정확히_한_번만_만들어진다() throws Exception {
        Member admin = member(MemberRole.ADMIN);
        Member target = member(MemberRole.USER);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                Long shortId = sanctionService.createManual(admin.getMemberId(), target.getMemberId(), 3, "반복 욕설")
                        .sanctionId();
                Long longId = sanctionService.createManual(admin.getMemberId(), target.getMemberId(), 30, "반복 욕설")
                        .sanctionId();

                CyclicBarrier startTogether = new CyclicBarrier(2);
                Future<?> first = executor.submit(() -> releaseTogether(startTogether, admin, shortId));
                Future<?> second = executor.submit(() -> releaseTogether(startTogether, admin, longId));
                first.get(30, TimeUnit.SECONDS);
                second.get(30, TimeUnit.SECONDS);

                List<Notification> released = notificationRepository.findAll().stream()
                        .filter(notification -> notification.getType() == NotificationType.COMMENT_RESTRICTION_RELEASED)
                        .filter(notification -> shortId.equals(notification.getSanctionId())
                                || longId.equals(notification.getSanctionId()))
                        .toList();
                assertThat(released).as("round %d: 두 제한 중 마지막으로 풀린 쪽만 해제 알림을 만든다", round).hasSize(1);
                assertThat(released.get(0).getMemberId()).isEqualTo(target.getMemberId());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private Void releaseTogether(CyclicBarrier startTogether, Member admin, Long sanctionId) throws Exception {
        startTogether.await(10, TimeUnit.SECONDS);
        sanctionService.release(admin.getMemberId(), sanctionId);
        return null;
    }

    private Member member(MemberRole role) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Member member = new Member("sanction-release-" + suffix, null, null, role);
        member.setInitialNickname(MemberNickname.of("release" + suffix));
        member = memberRepository.saveAndFlush(member);
        members.add(member);
        return member;
    }
}
