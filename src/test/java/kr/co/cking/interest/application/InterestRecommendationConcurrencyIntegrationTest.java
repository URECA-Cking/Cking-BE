package kr.co.cking.interest.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
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
import kr.co.cking.interest.domain.InterestCategory;
import kr.co.cking.interest.domain.InterestTaxonomy;
import kr.co.cking.interest.domain.InterestTaxonomyHash;
import kr.co.cking.interest.domain.InterestTaxonomyHash.Row;
import kr.co.cking.interest.repository.InterestCategoryRepository;
import kr.co.cking.interest.repository.InterestTaxonomyRepository;
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

    private static final String CODE = "SPORTS";

    @Autowired InterestRecommendationResultService service;
    @Autowired InterestTaxonomyRepository taxonomyRepository;
    @Autowired InterestCategoryRepository categoryRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired CreatorRepository creatorRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private String version;
    private Long memberId;
    private Long creatorId;

    /** 테스트 전용 분류체계 버전의 행만 지운다. 공유 DB의 실제 v0.2 적재 결과는 건드리지 않는다. */
    @AfterEach
    void cleanUp() {
        if (version != null) {
            jdbcTemplate.update("delete from interest_recommendation_state where taxonomy_version = ?", version);
            jdbcTemplate.update("delete from interest_recommendation_candidate where generation_id in "
                    + "(select generation_id from interest_recommendation_generation where taxonomy_version = ?)", version);
            jdbcTemplate.update("delete from interest_recommendation_generation where taxonomy_version = ?", version);
            jdbcTemplate.update("delete from interest_category where taxonomy_version = ?", version);
            jdbcTemplate.update("delete from interest_taxonomy where taxonomy_version = ?", version);
        }
        if (creatorId != null) {
            jdbcTemplate.update("delete from creator where creator_id = ?", creatorId);
        }
        if (memberId != null) {
            jdbcTemplate.update("delete from member where member_id = ?", memberId);
        }
    }

    @Test
    void 같은_분야의_같은_입력_동시_적재는_하나의_세대로_수렴한다() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        version = "t" + suffix.substring(0, 12);
        String taxonomyHash = InterestTaxonomyHash.compute(List.of(new Row(CODE, "스포츠", "설명")));
        taxonomyRepository.saveAndFlush(new InterestTaxonomy(version, taxonomyHash, false, Instant.now()));
        categoryRepository.saveAndFlush(new InterestCategory(version, CODE, "스포츠", "설명", 1, true));
        Member owner = memberRepository.saveAndFlush(new Member("owner-" + suffix.substring(0, 12), null, null, MemberRole.USER));
        memberId = owner.getMemberId();
        creatorId = creatorRepository.saveAndFlush(new Creator(memberId, "c-" + suffix.substring(0, 12))).getCreatorId();
        String hash = (suffix + suffix).substring(0, 64);
        InterestRecommendationCommand command = new InterestRecommendationCommand(1L,
                version, taxonomyHash, CODE, "INTEREST_M3_V1", "model-v1", hash,
                List.of(new InterestRecommendationCommand.Candidate(
                        CODE, creatorId, new BigDecimal("0.90000000"), 1, "INTEREST_M3_V1", "model-v1", hash)));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<StoreResult>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return service.replace(CODE, command);
                }));
            }
            start.countDown();
            List<StoreResult> results = List.of(futures.get(0).get(10, TimeUnit.SECONDS),
                    futures.get(1).get(10, TimeUnit.SECONDS));

            assertThat(results).extracting(StoreResult::applied).containsExactlyInAnyOrder(true, false);
            assertThat(results).extracting(StoreResult::generationId).containsOnly(results.getFirst().generationId());
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from interest_recommendation_generation where taxonomy_version = ?",
                    Integer.class, version)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from interest_recommendation_candidate where generation_id = ?",
                    Integer.class, results.getFirst().generationId())).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }
}
