package kr.co.cking.follow.application;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.follow.application.dto.FollowedCreatorView;
import kr.co.cking.follow.repository.CreatorFollowRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 MySQL에서 팔로우의 멱등성·동시성과 목록 정렬을 확인한다. */
@SpringBootTest
class CreatorFollowIntegrationTest {

    @Autowired
    private CreatorFollowService followService;
    @Autowired
    private CreatorFollowQueryService followQueryService;
    @Autowired
    private CreatorFollowRepository followRepository;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private CreatorRepository creatorRepository;

    private final List<Member> members = new ArrayList<>();
    private final List<Creator> creators = new ArrayList<>();
    private Member fan;

    @BeforeEach
    void setUp() {
        fan = member("fan");
    }

    @AfterEach
    void cleanUp() {
        for (Creator creator : creators) {
            followService.unfollow(fan.getMemberId(), creator.getCreatorId());
        }
        creatorRepository.deleteAll(creators);
        memberRepository.deleteAll(members);
    }

    @Test
    void 같은_크리에이터를_여러_번_팔로우해도_관계는_하나이고_언팔로우도_멱등하다() {
        Creator creator = creator("repeat");

        followService.follow(fan.getMemberId(), creator.getCreatorId());
        followService.follow(fan.getMemberId(), creator.getCreatorId());

        assertThat(countFollows(creator)).isEqualTo(1);
        assertThat(followQueryService.isFollowing(fan.getMemberId(), creator.getCreatorId())).isTrue();

        followService.unfollow(fan.getMemberId(), creator.getCreatorId());
        followService.unfollow(fan.getMemberId(), creator.getCreatorId());

        assertThat(countFollows(creator)).isZero();
        assertThat(followQueryService.isFollowing(fan.getMemberId(), creator.getCreatorId())).isFalse();
    }

    @Test
    void 동시에_같은_팔로우_요청이_들어와도_관계는_하나만_생긴다() throws Exception {
        Creator creator = creator("concurrent");
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    followService.follow(fan.getMemberId(), creator.getCreatorId());
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(countFollows(creator)).isEqualTo(1);
    }

    @Test
    void 내_팔로우_목록은_최근_팔로우한_순서다() {
        Creator first = creator("first");
        Creator second = creator("second");
        followService.follow(fan.getMemberId(), first.getCreatorId());
        followService.follow(fan.getMemberId(), second.getCreatorId());

        List<FollowedCreatorView> items =
                followQueryService.findMine(fan.getMemberId(), PageRequest.of(0, 20)).getContent();

        assertThat(items).extracting(FollowedCreatorView::creatorId)
                .containsExactly(second.getCreatorId(), first.getCreatorId());
        assertThat(items).extracting(FollowedCreatorView::creatorName)
                .containsExactly(second.getName(), first.getName());
        assertThat(items).allSatisfy(item -> assertThat(item.followedAt()).isNotNull());
    }

    private long countFollows(Creator creator) {
        return followRepository.findAll().stream()
                .filter(follow -> follow.getCreatorId().equals(creator.getCreatorId())
                        && follow.getMemberId().equals(fan.getMemberId()))
                .count();
    }

    private Member member(String prefix) {
        Member member = memberRepository.saveAndFlush(
                new Member(prefix + "-" + suffix(), null, null, MemberRole.USER));
        members.add(member);
        return member;
    }

    private Creator creator(String prefix) {
        Member owner = member("owner-" + prefix);
        Creator creator = creatorRepository.saveAndFlush(new Creator(owner.getMemberId(), "creator-" + suffix()));
        creators.add(creator);
        return creator;
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
