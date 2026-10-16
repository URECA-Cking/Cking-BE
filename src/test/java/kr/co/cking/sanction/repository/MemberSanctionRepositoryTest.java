package kr.co.cking.sanction.repository;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.sanction.domain.MemberSanction;
import kr.co.cking.sanction.domain.SanctionScope;
import kr.co.cking.sanction.domain.SanctionSource;
import kr.co.cking.sanction.domain.SanctionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * 실제 MySQL에서 제재 조회 쿼리와 제약을 확인한다(이슈 #569). 경고 집계 기간 경계, 유효한 제한 중 가장 늦은 종료
 * 시각, 해제한 제한 제외, 목록 필터(null 파라미터 포함), 같은 원인 댓글의 중복 경고 거부를 다룬다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class MemberSanctionRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");
    // 원인 댓글 ID는 전체 테이블에서 유일해야 하므로 개발 DB의 기존 데이터와 겹치지 않도록 큰 값에 더해 쓴다.
    private static final long CAUSE_BASE = 9_000_000_000L;

    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private MemberSanctionRepository sanctionRepository;
    @Autowired
    private CreatorRepository creatorRepository;

    private Long spaceCreatorId;
    private Long otherSpaceCreatorId;
    private Long memberId;
    private Long otherMemberId;
    private Long adminId;

    @BeforeEach
    void setUp() {
        memberId = memberRepository.saveAndFlush(new Member("제재 테스트 회원", null, null, MemberRole.USER)).getMemberId();
        otherMemberId = memberRepository.saveAndFlush(new Member("제재 테스트 다른 회원", null, null, MemberRole.USER)).getMemberId();
        adminId = memberRepository.saveAndFlush(new Member("제재 테스트 관리자", null, null, MemberRole.ADMIN)).getMemberId();
        spaceCreatorId = space("제재 테스트 스페이스 주인 A");
        otherSpaceCreatorId = space("제재 테스트 스페이스 주인 B");
    }

    @Test
    void 경고_횟수는_그_스페이스에서_집계_시작_시각_이상의_자동_경고만_센다() {
        Instant since = NOW.minus(Duration.ofDays(90));
        automatic(memberId, 1L, since.minusMillis(1));
        automatic(memberId, 2L, since);
        automatic(memberId, 3L, NOW.minus(Duration.ofDays(10)));
        manual(memberId, NOW.minus(Duration.ofDays(1)), Duration.ofDays(3));
        automatic(otherMemberId, 4L, NOW.minus(Duration.ofDays(1)));
        // 다른 스페이스의 경고는 이 스페이스의 횟수에 세지 않는다.
        automaticIn(otherSpaceCreatorId, memberId, 5L, NOW.minus(Duration.ofDays(2)));

        assertThat(sanctionRepository.countByMemberIdAndSourceAndSpaceCreatorIdAndStartedAtGreaterThanEqual(
                memberId, SanctionSource.AUTOMATIC, spaceCreatorId, since)).isEqualTo(2);
        assertThat(sanctionRepository.countByMemberIdAndSourceAndSpaceCreatorIdAndStartedAtGreaterThanEqual(
                memberId, SanctionSource.AUTOMATIC, otherSpaceCreatorId, since)).isEqualTo(1);
    }

    @Test
    void 유효한_제한이_여럿이면_가장_늦게_끝나는_시각을_돌려준다() {
        MemberSanction shortOne = manual(memberId, NOW.minus(Duration.ofHours(1)), Duration.ofDays(1));
        MemberSanction longOne = manual(memberId, NOW.minus(Duration.ofHours(2)), Duration.ofDays(7));

        assertThat(active(memberId)).contains(longOne.getEndsAt());
        assertThat(shortOne.getEndsAt()).isBefore(longOne.getEndsAt());
    }

    @Test
    void 끝났거나_아직_시작하지_않았거나_해제한_제한은_유효하지_않다() {
        manual(memberId, NOW.minus(Duration.ofDays(3)), Duration.ofDays(1));
        manual(memberId, NOW.plus(Duration.ofHours(1)), Duration.ofDays(1));
        MemberSanction released = manual(memberId, NOW.minus(Duration.ofHours(1)), Duration.ofDays(7));
        released.release(adminId, NOW.minus(Duration.ofMinutes(1)));
        sanctionRepository.flush();

        assertThat(active(memberId)).isEmpty();
    }

    @Test
    void 종료_시각_정각에는_유효하지_않고_직전까지는_유효하다() {
        MemberSanction sanction = manual(memberId, NOW.minus(Duration.ofDays(1)), Duration.ofDays(1));

        assertThat(sanctionRepository.findActiveEndsAt(memberId, SanctionScope.PLATFORM,
                SanctionType.COMMENT_RESTRICTION, sanction.getEndsAt())).isEmpty();
        assertThat(sanctionRepository.findActiveEndsAt(memberId, SanctionScope.PLATFORM,
                SanctionType.COMMENT_RESTRICTION, sanction.getEndsAt().minusMillis(1))).isPresent();
    }

    @Test
    void 다른_회원의_제한은_영향을_주지_않는다() {
        manual(otherMemberId, NOW.minus(Duration.ofHours(1)), Duration.ofDays(1));

        assertThat(active(memberId)).isEmpty();
    }

    @Test
    void 목록은_최신_시작_순이고_회원과_유효_여부로_거른다() {
        MemberSanction oldExpired = manual(memberId, NOW.minus(Duration.ofDays(30)), Duration.ofDays(1));
        MemberSanction recentActive = manual(memberId, NOW.minus(Duration.ofHours(1)), Duration.ofDays(2));
        MemberSanction others = manual(otherMemberId, NOW.minus(Duration.ofHours(2)), Duration.ofDays(1));
        PageRequest page = PageRequest.of(0, 20);

        Page<MemberSanction> all = sanctionRepository.search(null, false, NOW, page);
        Page<MemberSanction> ofMember = sanctionRepository.search(memberId, false, NOW, page);
        Page<MemberSanction> activeOfMember = sanctionRepository.search(memberId, true, NOW, page);
        Page<MemberSanction> activeAll = sanctionRepository.search(null, true, NOW, page);

        assertThat(all.getContent()).extracting(MemberSanction::getSanctionId)
                .containsSubsequence(recentActive.getSanctionId(), others.getSanctionId(), oldExpired.getSanctionId());
        assertThat(ofMember.getContent()).extracting(MemberSanction::getSanctionId)
                .containsExactly(recentActive.getSanctionId(), oldExpired.getSanctionId());
        assertThat(activeOfMember.getContent()).extracting(MemberSanction::getSanctionId)
                .containsExactly(recentActive.getSanctionId());
        assertThat(activeAll.getContent()).extracting(MemberSanction::getSanctionId)
                .contains(recentActive.getSanctionId(), others.getSanctionId())
                .doesNotContain(oldExpired.getSanctionId());
        assertThat(ofMember.getTotalElements()).isEqualTo(2);
    }

    @Test
    void 같은_원인_댓글로_경고를_두_번_저장하면_유니크_제약이_거부한다() {
        automatic(memberId, 1L, NOW.minusSeconds(60));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> automatic(memberId, 1L, NOW))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void 수동_제한은_원인_댓글이_없어도_여러_건_저장할_수_있다() {
        manual(memberId, NOW, Duration.ofDays(1));
        manual(memberId, NOW, Duration.ofDays(2));

        assertThat(sanctionRepository.search(memberId, false, NOW.plusSeconds(1), PageRequest.of(0, 20)).getTotalElements())
                .isEqualTo(2);
    }

    @Test
    void 해제는_잠금_조회로_읽고_해제_정보를_저장한다() {
        MemberSanction sanction = manual(memberId, NOW.minus(Duration.ofHours(1)), Duration.ofDays(7));

        MemberSanction locked = sanctionRepository.findByIdForUpdate(sanction.getSanctionId()).orElseThrow();
        locked.release(adminId, NOW);
        sanctionRepository.flush();

        MemberSanction reloaded = sanctionRepository.findById(sanction.getSanctionId()).orElseThrow();
        assertThat(reloaded.getReleasedAt()).isEqualTo(NOW);
        assertThat(reloaded.getReleasedBy()).isEqualTo(adminId);
    }

    private Optional<Instant> active(Long member) {
        return sanctionRepository.findActiveEndsAt(member, SanctionScope.PLATFORM, SanctionType.COMMENT_RESTRICTION, NOW);
    }

    private MemberSanction automatic(Long member, Long causeCommentId, Instant startedAt) {
        return automatic(member, causeCommentId, startedAt, Duration.ofDays(1));
    }

    private MemberSanction automatic(Long member, Long causeCommentId, Instant startedAt, Duration duration) {
        return sanctionRepository.saveAndFlush(MemberSanction.automaticSpace(
                member, spaceCreatorId, CAUSE_BASE + causeCommentId, adminId, startedAt, duration));
    }

    private MemberSanction automaticIn(Long spaceCreator, Long member, Long causeCommentId, Instant startedAt) {
        return sanctionRepository.saveAndFlush(MemberSanction.automaticSpace(
                member, spaceCreator, CAUSE_BASE + causeCommentId, adminId, startedAt, Duration.ofDays(1)));
    }

    /** 스페이스 범위 제재가 가리킬 크리에이터를 만든다(space_creator_id는 creator 테이블을 참조한다). */
    private Long space(String ownerName) {
        Member owner = memberRepository.saveAndFlush(new Member(ownerName, null, null, MemberRole.USER));
        return creatorRepository.saveAndFlush(
                new Creator(owner.getMemberId(), "sanction-space-" + owner.getMemberId())).getCreatorId();
    }

    private MemberSanction manual(Long member, Instant startedAt, Duration duration) {
        return sanctionRepository.saveAndFlush(MemberSanction.manual(member, adminId, "사유", startedAt, duration));
    }
}
