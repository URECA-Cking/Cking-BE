package kr.co.cking.member.application;

import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberErrorCode;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 서로 다른 회원이 같은 닉네임을 선점해도 DB UNIQUE로 한 명만 남는지 검증한다. */
@SpringBootTest(properties = "cking.scheduling.enabled=false")
class MemberNicknameConcurrencyIntegrationTest {

    @Autowired
    private MemberNicknameService nicknameService;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> memberIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long memberId : memberIds) {
            jdbcTemplate.update("delete from member_nickname_reservation where member_id = ?", memberId);
            jdbcTemplate.update("delete from member where member_id = ?", memberId);
        }
    }

    @Test
    void 서로_다른_회원의_동일_닉네임_동시_설정은_한_명만_성공한다() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Long first = createMember("first-" + suffix);
        Long second = createMember("second-" + suffix);
        String nickname = "race" + suffix;

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ChangeOutcome>> results = List.of(
                    executor.submit(() -> changeAfter(start, first, nickname)),
                    executor.submit(() -> changeAfter(start, second, nickname))
            );
            start.countDown();

            long successfulCommands = 0;
            List<MemberErrorCode> failures = new ArrayList<>();
            for (Future<ChangeOutcome> result : results) {
                ChangeOutcome outcome = result.get(10, TimeUnit.SECONDS);
                if (outcome.success()) {
                    successfulCommands++;
                } else {
                    failures.add(outcome.errorCode());
                }
            }

            assertThat(successfulCommands).isEqualTo(1);
            assertThat(failures).containsExactly(MemberErrorCode.NICKNAME_ALREADY_TAKEN);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from member where nickname_normalized = ?", Integer.class, nickname))
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private Long createMember(String name) {
        Long memberId = memberRepository.saveAndFlush(new Member(name, null, null, MemberRole.USER)).getMemberId();
        memberIds.add(memberId);
        return memberId;
    }

    private ChangeOutcome changeAfter(CountDownLatch start, Long memberId, String nickname) throws InterruptedException {
        start.await();
        try {
            nicknameService.change(memberId, nickname);
            return new ChangeOutcome(true, null);
        } catch (kr.co.cking.common.exception.BusinessException exception) {
            return new ChangeOutcome(false, (MemberErrorCode) exception.getErrorCode());
        }
    }

    private record ChangeOutcome(boolean success, MemberErrorCode errorCode) {
    }
}
