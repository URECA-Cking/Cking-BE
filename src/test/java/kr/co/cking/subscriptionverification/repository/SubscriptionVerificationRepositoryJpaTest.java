package kr.co.cking.subscriptionverification.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.mission.Mission;
import kr.co.cking.mission.MissionRepository;
import kr.co.cking.mission.domain.MissionType;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerification;
import kr.co.cking.subscriptionverification.domain.SubscriptionVerificationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SubscriptionVerificationRepositoryJpaTest {

    private static final Instant BASE_TIME = Instant.parse("2026-09-28T00:00:00Z");
    private static final List<SubscriptionVerificationStatus> ACTIVE_STATUSES = List.of(
            SubscriptionVerificationStatus.PENDING,
            SubscriptionVerificationStatus.PROCESSING
    );

    @Autowired private SubscriptionVerificationRepository verificationRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private CreatorRepository creatorRepository;
    @Autowired private MissionRepository missionRepository;

    private Long memberId;
    private Long otherMemberId;
    private Long creatorId;
    private Long missionId;

    @BeforeEach
    void setUp() {
        Member creatorMember = memberRepository.saveAndFlush(
                new Member("인증 크리에이터", null, "creator-verification@example.com", MemberRole.USER)
        );
        Creator creator = creatorRepository.saveAndFlush(new Creator(creatorMember.getMemberId(), "인증 채널"));
        Mission mission = missionRepository.saveAndFlush(
                new Mission(creator.getCreatorId(), MissionType.YOUTUBE_SUBSCRIPTION, 1, null, null)
        );
        Member participant = memberRepository.saveAndFlush(
                new Member("인증 사용자", null, "verification-user@example.com", MemberRole.USER)
        );
        Member otherParticipant = memberRepository.saveAndFlush(
                new Member("다른 인증 사용자", null, "other-verification-user@example.com", MemberRole.USER)
        );
        memberId = participant.getMemberId();
        otherMemberId = otherParticipant.getMemberId();
        creatorId = creator.getCreatorId();
        missionId = mission.getMissionId();
    }

    @Test
    void requestId와_업무_상태별로_인증을_조회한다() {
        SubscriptionVerification saved = verificationRepository.saveAndFlush(newVerification(BASE_TIME));

        assertThat(verificationRepository.findByRequestId(saved.getRequestId())).contains(saved);
        assertThat(verificationRepository.existsByMemberIdAndCreatorIdAndMissionIdAndStatusIn(
                memberId, creatorId, missionId, ACTIVE_STATUSES
        )).isTrue();
        assertThat(verificationRepository.existsByCreatorIdAndStatusIn(creatorId, ACTIVE_STATUSES)).isTrue();
        assertThat(verificationRepository.findFirstByMemberIdAndCreatorIdAndMissionIdOrderByCreatedAtDescVerificationIdDesc(
                memberId, creatorId, missionId
        )).contains(saved);
    }

    @Test
    void 같은_사용자_Creator_Mission에는_활성_인증이_하나만_존재한다() {
        verificationRepository.saveAndFlush(newVerification(BASE_TIME));

        assertThatThrownBy(() -> verificationRepository.saveAndFlush(newVerification(BASE_TIME.plusSeconds(1))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 같은_사용자_Creator_Mission에는_APPROVED가_하나만_존재한다() {
        SubscriptionVerification first = verificationRepository.saveAndFlush(newVerification(BASE_TIME));
        approve(first, BASE_TIME.plusSeconds(1));
        verificationRepository.flush();

        SubscriptionVerification second = verificationRepository.saveAndFlush(newVerification(BASE_TIME.plusSeconds(2)));
        approve(second, BASE_TIME.plusSeconds(3));

        assertThatThrownBy(verificationRepository::flush)
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 종료된_비승인_이력은_여러_건_저장하고_가장_최근_이력을_조회한다() {
        SubscriptionVerification first = verificationRepository.saveAndFlush(newVerification(BASE_TIME));
        reject(first, BASE_TIME.plusSeconds(1));
        verificationRepository.flush();

        SubscriptionVerification second = verificationRepository.saveAndFlush(newVerification(BASE_TIME));
        reject(second, BASE_TIME.plusSeconds(3));
        verificationRepository.flush();

        assertThat(verificationRepository.findFirstByMemberIdAndCreatorIdAndMissionIdOrderByCreatedAtDescVerificationIdDesc(
                memberId, creatorId, missionId
        )).contains(second);
        assertThat(verificationRepository.count()).isEqualTo(2);
    }

    @Test
    void requestId는_전체_인증에서_유일하다() {
        SubscriptionVerification first = newVerification(BASE_TIME);
        verificationRepository.saveAndFlush(first);

        SubscriptionVerification duplicate = verification(
                otherMemberId,
                first.getRequestId(),
                UUID.randomUUID().toString(),
                BASE_TIME.plusSeconds(1)
        );

        assertThatThrownBy(() -> verificationRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 승인_조회는_APPROVED만_반환한다() {
        SubscriptionVerification verification = verificationRepository.saveAndFlush(newVerification(BASE_TIME));
        approve(verification, BASE_TIME.plusSeconds(1));
        verificationRepository.flush();

        assertThat(verificationRepository.findByMemberIdAndCreatorIdAndMissionIdAndStatus(
                memberId, creatorId, missionId, SubscriptionVerificationStatus.APPROVED
        )).contains(verification);
        assertThat(verificationRepository.existsByMemberIdAndCreatorIdAndMissionIdAndStatusIn(
                memberId, creatorId, missionId, ACTIVE_STATUSES
        )).isFalse();
    }

    private SubscriptionVerification newVerification(Instant createdAt) {
        return verification(UUID.randomUUID().toString(), UUID.randomUUID().toString(), createdAt);
    }

    private SubscriptionVerification verification(String requestId, String rewardRequestId, Instant createdAt) {
        return verification(memberId, requestId, rewardRequestId, createdAt);
    }

    private SubscriptionVerification verification(
            Long targetMemberId,
            String requestId,
            String rewardRequestId,
            Instant createdAt
    ) {
        return SubscriptionVerification.pending(
                targetMemberId,
                creatorId,
                missionId,
                requestId,
                "a".repeat(64),
                "예상치 못한 필름",
                "@unexpectedfilm",
                "subscription-verifications/2026/09/" + requestId + "/image.jpg",
                "b".repeat(64),
                "JPEG_V1",
                rewardRequestId,
                createdAt
        );
    }

    private void approve(SubscriptionVerification verification, Instant startedAt) {
        String token = UUID.randomUUID().toString();
        verification.startProcessing(token, startedAt, startedAt.plusSeconds(60));
        verification.approve(token, startedAt.plusMillis(1));
    }

    private void reject(SubscriptionVerification verification, Instant startedAt) {
        String token = UUID.randomUUID().toString();
        verification.startProcessing(token, startedAt, startedAt.plusSeconds(60));
        verification.reject(token, "CHANNEL_MISMATCH", startedAt.plusMillis(1));
    }
}
