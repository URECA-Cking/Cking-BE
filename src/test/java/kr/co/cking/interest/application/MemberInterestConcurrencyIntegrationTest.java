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
    private static final List<String> FIRST_SUB = List.of("FITNESS_YOGA", "FOOD_BAKING", "GAME_RPG");
    private static final List<String> SECOND_SUB = List.of("MUSIC_VOCAL", "PET_DOG", "TECH_AI");

    @Autowired MemberInterestService service;
    @Autowired MemberRepository memberRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private Long memberId;

    @AfterEach
    void cleanUp() {
        if (memberId != null) {
            jdbcTemplate.update("delete from member_interest_subtopic where member_id = ?", memberId);
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
                for (int pair = 0; pair < 2; pair++) {
                    List<String> codes = pair == 0 ? FIRST : SECOND;
                    List<String> subtopics = pair == 0 ? FIRST_SUB : SECOND_SUB;
                    futures.add(executor.submit(() -> {
                        start.await();
                        return service.replace(memberId, "v0.2", codes, "subtopic-v1", subtopics);
                    }));
                }
                start.countDown();
                for (Future<?> future : futures) {
                    future.get();
                }

                List<String> stored = service.findMine(memberId).interestCodes();
                assertThat(stored).hasSizeLessThanOrEqualTo(3);
                assertThat(stored).isIn(FIRST, SECOND);
                // 상위와 세부는 같은 요청의 쌍이어야 한다(서로 다른 요청의 선택이 섞이지 않는다).
                assertThat(service.findMine(memberId).subtopicCodes())
                        .isEqualTo(stored.equals(FIRST) ? FIRST_SUB : SECOND_SUB);
                assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from member_interest_subtopic where member_id = ?", Integer.class, memberId))
                        .isEqualTo(3);
                assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from member_interest where member_id = ?", Integer.class, memberId))
                        .isEqualTo(3);
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
