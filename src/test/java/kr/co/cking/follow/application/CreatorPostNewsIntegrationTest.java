package kr.co.cking.follow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.follow.application.dto.CreatorPostNewsView;
import kr.co.cking.follow.domain.FollowErrorCode;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.post.domain.CreatorPost;
import kr.co.cking.post.domain.PostVisibility;
import kr.co.cking.post.repository.CreatorPostRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 실제 DB에서 팔로우 시작 시각, 공개 범위, 읽음 갱신을 함께 검증한다. */
@SpringBootTest
class CreatorPostNewsIntegrationTest {

    @Autowired private CreatorPostNewsService newsService;
    @Autowired private CreatorFollowService followService;
    @Autowired private CreatorRepository creatorRepository;
    @Autowired private CreatorPostRepository postRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private JdbcTemplate jdbc;

    private final List<Member> members = new ArrayList<>();
    private final List<Creator> creators = new ArrayList<>();
    private final List<CreatorPost> posts = new ArrayList<>();
    private Member fan;

    @AfterEach
    void cleanUp() {
        for (CreatorPost post : posts) {
            jdbc.update("DELETE FROM creator_post WHERE post_id = ?", post.getPostId());
        }
        if (fan != null) {
            for (Creator creator : creators) {
                followService.unfollow(fan.getMemberId(), creator.getCreatorId());
            }
        }
        creatorRepository.deleteAll(creators);
        memberRepository.deleteAll(members);
    }

    @Test
    void 읽음_기록이_없으면_현재_팔로우_시작_이후_글만_NEW다() {
        fan = member();
        Creator creator = creator();
        followService.follow(fan.getMemberId(), creator.getCreatorId());
        Instant cutoff = Instant.parse("2026-10-18T00:00:00Z");
        setFollowStart(creator, cutoff);
        post(creator, PostVisibility.PUBLIC, cutoff.minusSeconds(1));

        assertThat(news().getFirst().hasUnreadPosts()).isFalse();
        assertThat(news().getFirst().latestPostCreatedAt()).isEqualTo(cutoff.minusSeconds(1));

        post(creator, PostVisibility.FOLLOWERS, cutoff.plusSeconds(1));
        assertThat(news().getFirst().hasUnreadPosts()).isTrue();
        assertThat(news().getFirst().latestPostCreatedAt()).isEqualTo(cutoff.plusSeconds(1));
    }

    @Test
    void 읽음은_반복과_오래된_시각에도_뒤로_가지_않고_재팔로우하면_초기화된다() {
        fan = member();
        Creator creator = creator();
        followService.follow(fan.getMemberId(), creator.getCreatorId());

        Instant first = newsService.markRead(fan.getMemberId(), creator.getCreatorId());
        Instant repeated = newsService.markRead(fan.getMemberId(), creator.getCreatorId());
        assertThat(repeated).isAfterOrEqualTo(first);

        Instant future = first.plusSeconds(3600);
        jdbc.update("UPDATE creator_follow SET last_read_at = ? WHERE member_id = ? AND creator_id = ?",
                Timestamp.from(future), fan.getMemberId(), creator.getCreatorId());
        assertThat(newsService.markRead(fan.getMemberId(), creator.getCreatorId())).isEqualTo(future);

        followService.unfollow(fan.getMemberId(), creator.getCreatorId());
        followService.follow(fan.getMemberId(), creator.getCreatorId());
        assertThat(jdbc.queryForObject("SELECT last_read_at FROM creator_follow WHERE member_id = ? AND creator_id = ?",
                Timestamp.class, fan.getMemberId(), creator.getCreatorId())).isNull();
    }

    @Test
    void 읽으면_NEW가_사라지고_그후_새로_작성된_글은_다시_NEW다() {
        fan = member();
        Creator creator = creator();
        followService.follow(fan.getMemberId(), creator.getCreatorId());
        Instant old = Instant.parse("2026-10-18T00:00:00Z");
        setFollowStart(creator, old);
        post(creator, PostVisibility.PUBLIC, old.plusSeconds(1));
        assertThat(news().getFirst().hasUnreadPosts()).isTrue();

        Instant readAt = newsService.markRead(fan.getMemberId(), creator.getCreatorId());
        assertThat(news().getFirst().hasUnreadPosts()).isFalse();

        post(creator, PostVisibility.FOLLOWERS, readAt.plusSeconds(1));
        assertThat(news().getFirst().hasUnreadPosts()).isTrue();
    }

    @Test
    void 병렬_읽음_요청도_같은_행을_공유하고_시각은_뒤로_가지_않는다() throws Exception {
        fan = member();
        Creator creator = creator();
        followService.follow(fan.getMemberId(), creator.getCreatorId());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Instant> first = executor.submit(() -> {
                start.await();
                return newsService.markRead(fan.getMemberId(), creator.getCreatorId());
            });
            Future<Instant> second = executor.submit(() -> {
                start.await();
                return newsService.markRead(fan.getMemberId(), creator.getCreatorId());
            });
            start.countDown();
            Instant firstAt = first.get(10, TimeUnit.SECONDS);
            Instant secondAt = second.get(10, TimeUnit.SECONDS);
            Instant stored = jdbc.queryForObject(
                    "SELECT last_read_at FROM creator_follow WHERE member_id = ? AND creator_id = ?",
                    Timestamp.class, fan.getMemberId(), creator.getCreatorId()).toInstant();
            assertThat(stored).isEqualTo(firstAt.isAfter(secondAt) ? firstAt : secondAt);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 삭제한_글과_비공개_글은_새_소식에서_제외한다() {
        fan = member();
        Creator creator = creator();
        followService.follow(fan.getMemberId(), creator.getCreatorId());
        Instant cutoff = Instant.parse("2026-10-18T00:00:00Z");
        setFollowStart(creator, cutoff);
        CreatorPost deleted = post(creator, PostVisibility.PUBLIC, cutoff.plusSeconds(1));
        postRepository.delete(deleted);
        posts.remove(deleted);

        assertThat(news().getFirst().hasUnreadPosts()).isFalse();
        assertThat(news().getFirst().latestPostCreatedAt()).isNull();

        CreatorPost hidden = post(creator, PostVisibility.PUBLIC, cutoff.plusSeconds(2));
        jdbc.update("UPDATE creator_post SET visibility = 'PRIVATE' WHERE post_id = ?", hidden.getPostId());
        assertThat(news().getFirst().hasUnreadPosts()).isFalse();
        assertThat(news().getFirst().latestPostCreatedAt()).isNull();
    }

    @Test
    void 비팔로우와_없는_크리에이터는_서로_다른_오류다() {
        fan = member();
        Creator creator = creator();
        assertThat(news()).isEmpty();
        assertThatThrownBy(() -> newsService.markRead(fan.getMemberId(), creator.getCreatorId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(FollowErrorCode.FOLLOW_REQUIRED));
        assertThatThrownBy(() -> newsService.markRead(fan.getMemberId(), Long.MAX_VALUE))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    private List<CreatorPostNewsView> news() {
        return newsService.findMine(fan.getMemberId());
    }

    private void setFollowStart(Creator creator, Instant at) {
        jdbc.update("UPDATE creator_follow SET created_at = ? WHERE member_id = ? AND creator_id = ?",
                Timestamp.from(at), fan.getMemberId(), creator.getCreatorId());
    }

    private CreatorPost post(Creator creator, PostVisibility visibility, Instant at) {
        CreatorPost post = postRepository.saveAndFlush(new CreatorPost(creator.getCreatorId(), "새 소식", visibility, at));
        posts.add(post);
        return post;
    }

    private Member member() {
        Member member = memberRepository.saveAndFlush(new Member("fan-" + UUID.randomUUID(), null, null, MemberRole.USER));
        members.add(member);
        return member;
    }

    private Creator creator() {
        Member owner = member();
        Creator creator = creatorRepository.saveAndFlush(new Creator(owner.getMemberId(), "creator-" + UUID.randomUUID()));
        creators.add(creator);
        return creator;
    }
}
