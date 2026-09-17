package kr.co.cking.common.seed;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import kr.co.cking.member.repository.MemberRepository;
import kr.co.cking.ticket.application.config.TicketRedisKeys;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code seed} 프로필을 명시적으로 켰을 때만 {@link DummyDataSeeder} 빈이 생성되므로,
 * 일반 {@code @SpringBootTest}(기본 local 프로필)에서는 이 시더가 자동 실행되지 않는다.
 * 이 테스트만 {@code seed}를 추가로 켜서 검증한다.
 */
@SpringBootTest
@ActiveProfiles({"local", "seed"})
class DummyDataSeederTest {

    private static final String DUMMY_EMAIL_LIKE = "dummy-%@cking.test";

    @Autowired
    private DummyDataSeeder seeder;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void cleanUp() {
        deleteDummyData();
    }

    @AfterEach
    void tearDown() {
        deleteDummyData();
    }

    @Test
    void 더미_유저_크리에이터_잔액을_DB와_Redis에_채운다() {
        seeder.run();

        List<Long> userIds = dummyMemberIds("dummy-user-%@cking.test");
        List<Long> creatorIds = jdbcTemplate.queryForList(
                "SELECT c.creator_id FROM creator c JOIN member m ON c.member_id = m.member_id "
                        + "WHERE m.email LIKE 'dummy-creator-%@cking.test'",
                Long.class);

        assertThat(userIds).hasSize(15);
        assertThat(creatorIds).hasSize(3);

        Long firstUser = userIds.get(0);
        Long firstCreator = creatorIds.get(0);
        Long balance = jdbcTemplate.queryForObject(
                "SELECT balance FROM user_ticket_balance WHERE member_id = ? AND creator_id = ?",
                Long.class, firstUser, firstCreator);
        assertThat(balance).isEqualTo(5L);
        assertThat(redisTemplate.opsForValue().get(TicketRedisKeys.balance(firstCreator, firstUser))).isEqualTo("5");
    }

    @Test
    void 다시_실행해도_중복_생성하지_않는다() {
        seeder.run();
        long userCountAfterFirstRun = memberRepository.count();

        seeder.run();

        assertThat(memberRepository.count()).isEqualTo(userCountAfterFirstRun);
    }

    // 중간 실패로 일부 더미 유저만 DB에 남은 상황(예: 15명 중 1명 누락)을 시뮬레이션한다.
    // find-or-create 방식이라 재실행하면 빠진 것만 채워야 하고, 이미 있던 나머지는
    // 그대로 유지돼야 한다(PR #83 리뷰: "user #1만 확인하고 skip"하던 예전 로직은
    // 이 경우 영원히 안 채워졌다).
    @Test
    void 일부_더미_유저가_누락된_상태에서_재실행하면_빠진_유저만_채운다() {
        seeder.run();
        List<Long> userIdsBeforeGap = dummyMemberIds("dummy-user-%@cking.test");
        Long removedUserId = userIdsBeforeGap.get(0);
        jdbcTemplate.update("DELETE FROM user_ticket_balance WHERE member_id = ?", removedUserId);
        jdbcTemplate.update("DELETE FROM member WHERE member_id = ?", removedUserId);

        seeder.run();

        List<Long> userIdsAfterRepair = dummyMemberIds("dummy-user-%@cking.test");
        assertThat(userIdsAfterRepair).hasSize(15);
        assertThat(userIdsAfterRepair).doesNotContain(removedUserId);
    }

    // Redis만 유실된 상황(DB는 온전)을 시뮬레이션한다. SET은 멱등이라 재실행하면
    // DB 재생성 없이도 Redis 값만 다시 채워져야 한다.
    @Test
    void Redis_잔액만_유실된_상태에서_재실행하면_Redis만_복구한다() {
        seeder.run();
        List<Long> userIds = dummyMemberIds("dummy-user-%@cking.test");
        List<Long> creatorIds = jdbcTemplate.queryForList(
                "SELECT c.creator_id FROM creator c JOIN member m ON c.member_id = m.member_id "
                        + "WHERE m.email LIKE 'dummy-creator-%@cking.test'",
                Long.class);
        String key = TicketRedisKeys.balance(creatorIds.get(0), userIds.get(0));
        redisTemplate.delete(key);
        long memberCountBeforeRepair = memberRepository.count();

        seeder.run();

        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("5");
        assertThat(memberRepository.count()).isEqualTo(memberCountBeforeRepair);
    }

    private List<Long> dummyMemberIds(String emailLike) {
        return jdbcTemplate.queryForList("SELECT member_id FROM member WHERE email LIKE ?", Long.class, emailLike);
    }

    private void deleteDummyData() {
        List<Long> creatorIds = jdbcTemplate.queryForList(
                "SELECT c.creator_id FROM creator c JOIN member m ON c.member_id = m.member_id "
                        + "WHERE m.email LIKE 'dummy-creator-%@cking.test'",
                Long.class);
        List<Long> userIds = dummyMemberIds("dummy-user-%@cking.test");

        for (Long creatorId : creatorIds) {
            for (Long userId : userIds) {
                redisTemplate.delete(TicketRedisKeys.balance(creatorId, userId));
            }
        }

        jdbcTemplate.update("DELETE FROM user_ticket_balance WHERE member_id IN "
                + "(SELECT member_id FROM member WHERE email LIKE 'dummy-user-%@cking.test')");
        jdbcTemplate.update("DELETE FROM creator WHERE member_id IN "
                + "(SELECT member_id FROM member WHERE email LIKE 'dummy-creator-%@cking.test')");
        jdbcTemplate.update("DELETE FROM member WHERE email LIKE ?", DUMMY_EMAIL_LIKE);
    }
}
