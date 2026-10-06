package kr.co.cking.interest.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 같은 회원의 서로 다른 목록 동시 저장이 회원 행 잠금으로 직렬화되어 선택이 섞이거나 상한을 넘지 않는지 확인한다. */
@SpringBootTest
class MemberInterestConcurrencyIntegrationTest {

    private static final List<String> FIRST = List.of("FITNESS", "FOOD", "GAME");
    private static final List<String> SECOND = List.of("MUSIC", "PET", "TECH");

    @Autowired MemberInterestService service;
    @Autowired MemberRepository memberRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private Long memberId;

    @AfterEach
    void cleanUp() {
        if (memberId != null) {
            jdbcTemplate.update("delete from member_interest where member_id = ?", memberId);
            jdbcTemplate.update("delete from member where member_id = ?", memberId);
        }
    }

    @Test
    void 동시_저장_후_선택은_한_요청의_목록과_정확히_같고_3개를_넘지_않는다() throws Exception {
        memberId = memberRepository.saveAndFlush(new Member(
                "관심분야동시성-" + UUID.randomUUID().toString().substring(0, 8), null, null, MemberRole.USER))
                .getMemberId();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> futures = new ArrayList<>();
                for (List<String> codes : List.of(FIRST, SECOND)) {
                    futures.add(executor.submit(() -> {
                        start.await();
                        return service.replace(memberId, "v0.2", codes);
                    }));
                }
                start.countDown();
                for (Future<?> future : futures) {
                    future.get();
                }

                List<String> stored = service.findMine(memberId).interestCodes();
                assertThat(stored).hasSizeLessThanOrEqualTo(3);
                assertThat(stored).isIn(FIRST, SECOND);
                assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from member_interest where member_id = ?", Integer.class, memberId))
                        .isEqualTo(3);
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
