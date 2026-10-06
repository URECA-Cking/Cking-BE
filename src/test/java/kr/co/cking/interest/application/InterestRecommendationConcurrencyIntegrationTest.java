package kr.co.cking.interest.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.interest.application.InterestRecommendationResultService.StoreResult;
import kr.co.cking.interest.application.dto.InterestRecommendationCommand;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 같은 분야에 같은 묶음을 동시에 적재해도 분야 행 잠금으로 하나의 세대로 수렴하는지 확인한다. */
@SpringBootTest
class InterestRecommendationConcurrencyIntegrationTest {

    @Autowired InterestRecommendationResultService service;
    @Autowired MemberRepository memberRepository;
    @Autowired CreatorRepository creatorRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private final List<Long> memberIds = new ArrayList<>();
    private final List<Long> creatorIds = new ArrayList<>();
    private final List<Long> generationIds = new ArrayList<>();

    /** 이 테스트가 만든 행만 지운다(공유 DB의 다른 적재 결과를 건드리지 않는다). */
    @AfterEach
    void cleanUp() {
        generationIds.forEach(id -> {
            jdbcTemplate.update("delete from interest_recommendation_state where current_generation_id = ?", id);
            jdbcTemplate.update("delete from interest_recommendation_candidate where generation_id = ?", id);
            jdbcTemplate.update("delete from interest_recommendation_generation where generation_id = ?", id);
        });
        creatorIds.forEach(id -> jdbcTemplate.update("delete from creator where creator_id = ?", id));
        memberIds.forEach(id -> jdbcTemplate.update("delete from member where member_id = ?", id));
    }

    @Test
    void 같은_분야의_같은_입력_동시_적재는_하나의_세대로_수렴한다() throws Exception {
        String taxonomyHash = Files.readString(Path.of("src/test/resources/fixtures/taxonomy/v02.sha256.txt")).strip();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Member owner = memberRepository.saveAndFlush(new Member("owner-" + suffix, null, null, MemberRole.USER));
        memberIds.add(owner.getMemberId());
        Creator creator = creatorRepository.saveAndFlush(new Creator(owner.getMemberId(), "c-" + suffix));
        creatorIds.add(creator.getCreatorId());
        String hash = "d".repeat(64);
        InterestRecommendationCommand command = new InterestRecommendationCommand(
                "v0.2", taxonomyHash, "SPORTS", "INTEREST_M3_V1", "model-v1", hash,
                List.of(new InterestRecommendationCommand.Candidate(
                        "SPORTS", creator.getCreatorId(), new BigDecimal("0.90000000"), 1,
                        "INTEREST_M3_V1", "model-v1", hash)));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<StoreResult>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return service.replace("SPORTS", command);
                }));
            }
            start.countDown();
            List<StoreResult> results = List.of(futures.get(0).get(10, TimeUnit.SECONDS),
                    futures.get(1).get(10, TimeUnit.SECONDS));

            results.forEach(result -> generationIds.add(result.generationId()));
            assertThat(results).extracting(StoreResult::applied).containsExactlyInAnyOrder(true, false);
            assertThat(results).extracting(StoreResult::generationId).containsOnly(results.getFirst().generationId());
            Long generationId = results.getFirst().generationId();
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from interest_recommendation_generation where input_hash = ?",
                    Integer.class, hash)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from interest_recommendation_candidate where generation_id = ?",
                    Integer.class, generationId)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }
}
