package kr.co.cking.creator.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import kr.co.cking.creator.application.SavedTagRecommendationService.Score;
import kr.co.cking.creator.domain.*;
import kr.co.cking.member.domain.*;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static kr.co.cking.creator.application.ContentTagService.encode;

/** Ordinary CI MySQL/Flyway test; no external model or LLM repository is required. */
@SpringBootTest
@Transactional
class SavedTagRecommendationRepositoryIntegrationTest {
    @Autowired SavedTagRecommendationRepository repository;
    @Autowired kr.co.cking.creator.application.SavedTagRecommendationService service;
    @Autowired MemberRepository members;
    @Autowired CreatorRepository creators;
    @Autowired CreatorSpaceRepository spaces;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    final String taxonomy = "a".repeat(64), config = "b".repeat(64);

    long fixture(String parent) {
        String name = "tag-" + UUID.randomUUID().toString().substring(0, 8);
        var member = members.saveAndFlush(new Member(name, null, null, MemberRole.USER));
        var creator = creators.saveAndFlush(new Creator(member.getMemberId(), name));
        long id = creator.getCreatorId();
        spaces.saveAndFlush(CreatorSpace.fromTemplate(id,
            new CreatorSpaceTemplate(member.getMemberId(), "intro", "p", "b", "c-{creatorId}"), name));
        String revision = ContentIntro.sha256(name);
        String hash = ContentIntro.hash("intro");
        jdbc.update("""
            INSERT INTO creator_content_automatic(automatic_revision,creator_id,input_revision,input_hash,config_hash,payload_hash,payload,created_at)
            VALUES(?,?,1,?,?,?,'{}',UTC_TIMESTAMP(6))
            """, revision, id, hash, config, hash);
        jdbc.update("""
            INSERT INTO creator_content_confirmation(creator_id,automatic_revision,input_revision,input_hash,taxonomy_hash,tags,confirmed_at)
            VALUES(?,?,1,?,?,?,UTC_TIMESTAMP(6))
            """, id, revision, hash, taxonomy, encode(java.util.Map.of("parents", List.of(parent), "topics", List.of())));
        long confirmation = jdbc.queryForObject("SELECT confirmation_id FROM creator_content_confirmation WHERE creator_id=?", Long.class, id);
        jdbc.update("INSERT INTO creator_content_state(creator_id,automatic_revision,confirmation_id) VALUES(?,?,?)", id, revision, confirmation);
        return id;
    }

    @Test void selectedTagJoinAndCandidateFencesUseRealMysql() {
        long first = fixture("FOOD"), second = fixture("FOOD"), unrelated = fixture("GAME");
        var rows = repository.confirmed();
        repository.replaceScores(List.of(new Score("PARENT", "FOOD", first, new BigDecimal("0.8")),
            new Score("PARENT", "FOOD", second, new BigDecimal("0.3")),
            new Score("PARENT", "GAME", unrelated, BigDecimal.ONE)), rows, taxonomy, config, repository.ranking(false).sequence());
        var matches = repository.candidates(List.of("FOOD"), List.of(), taxonomy, config);
        assertThat(matches).extracting(c -> c.row().creatorId()).containsExactly(first, second);
        assertThat(matches).extracting(c -> c.score()).containsExactly(new BigDecimal("0.80000000"), new BigDecimal("0.30000000"));
        jdbc.update("UPDATE creator_space SET content_revision=2 WHERE creator_id=?", first);
        matches = repository.candidates(List.of("FOOD"), List.of(), taxonomy, config);
        assertThat(matches.get(0).score()).isNull();
        assertThat(matches.get(1).score()).isEqualByComparingTo("0.3");
        jdbc.update("UPDATE creator_content_state SET confirmation_id=NULL WHERE creator_id=?", first);
        assertThat(repository.candidates(List.of("FOOD"), List.of(), taxonomy, config))
            .extracting(c -> c.row().creatorId()).containsExactly(second);
        assertThat(repository.candidates(List.of("FOOD"), List.of(), "f".repeat(64), config))
            .allMatch(c -> c.score() == null);
    }

    @Test void serviceApplyPublishesCandidateIdentitiesAtomically() {
        long first = fixture("FOOD"), second = fixture("FOOD");
        String version = "saved-review-" + UUID.randomUUID();
        var definition = java.util.Map.of("code", "FOOD_BAKING", "parent", "FOOD", "name", "Baking", "definition", "Baking content");
        var snapshot = java.util.Map.of("version", version, "parents", List.of("FOOD"), "subtopics", List.of(definition));
        String hash = kr.co.cking.interest.domain.InterestSubtopicHash.compute(List.of(
            new kr.co.cking.interest.domain.InterestSubtopicHash.Row("FOOD_BAKING", "FOOD", "Baking", "Baking content")));
        jdbc.update("UPDATE creator_content_taxonomy SET active=FALSE WHERE active=TRUE");
        jdbc.update("""
            INSERT INTO creator_content_taxonomy(taxonomy_version,taxonomy_hash,criteria_hash,config_hash,parent_codes,topic_parents,taxonomy_snapshot,approved,active)
            VALUES(?,?,?,?,?,?,?,TRUE,TRUE)
            """, version, hash, kr.co.cking.creator.application.ContentTagService.digest(snapshot), config,
            encode(List.of("FOOD")), encode(java.util.Map.of("FOOD_BAKING", "FOOD")), encode(snapshot));
        jdbc.update("UPDATE creator_content_confirmation SET taxonomy_hash=? WHERE creator_id IN (?,?)", hash, first, second);
        var input = service.inputs();
        long sequence = ((Number) input.get("lastApplicationSequence")).longValue() + 1;
        var delivery = new kr.co.cking.creator.application.SavedTagRecommendationService.Delivery(sequence,
            (String) input.get("snapshotHash"), "SAVED_TAG_M2_V1", "fake",
            List.of(new Score("PARENT", "FOOD", first, BigDecimal.ONE), new Score("PARENT", "FOOD", second, BigDecimal.ZERO)));
        assertThat(service.apply(delivery)).containsEntry("applied", true);
        assertThat(service.apply(delivery)).containsEntry("applied", false);
        assertThat(repository.ranking(false).sequence()).isEqualTo(sequence);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM saved_tag_ranking_score WHERE application_sequence=?", Long.class, sequence)).isEqualTo(2);
        var result = service.find(999999, version, List.of("FOOD"), List.of(), 20);
        assertThat(kr.co.cking.creator.application.ContentTagService.node(result).path("items")).hasSize(2);
        jdbc.update("UPDATE creator_space SET content_revision=2, intro_text='changed' WHERE creator_id=?", first);
        result = service.find(999999, version, List.of("FOOD"), List.of(), 20);
        assertThat(result).containsEntry("rankingStatus", "READY");
        assertThat(kr.co.cking.creator.application.ContentTagService.node(result).path("items")).hasSize(1);
        // An older application can still write V91 during rolling deployment; its new sequence fences the V92 projection.
        jdbc.update("UPDATE saved_tag_ranking_state SET application_sequence=? WHERE state_id=1", sequence + 1);
        assertThat(service.find(999999, version, List.of("FOOD"), List.of(), 20)).containsEntry("rankingStatus", "PENDING");
    }

    @Test void confirmationChangeOnlyFencesChangedCandidate() {
        long first = fixture("FOOD"), second = fixture("FOOD");
        repository.replaceScores(List.of(new Score("PARENT", "FOOD", first, BigDecimal.ONE),
            new Score("PARENT", "FOOD", second, BigDecimal.ZERO)), repository.confirmed(), taxonomy, config, repository.ranking(false).sequence());
        jdbc.update("""
            INSERT INTO creator_content_confirmation(creator_id,automatic_revision,input_revision,input_hash,taxonomy_hash,tags,confirmed_at)
            SELECT creator_id,automatic_revision,input_revision,input_hash,taxonomy_hash,tags,UTC_TIMESTAMP(6)
            FROM creator_content_confirmation WHERE creator_id=?
            """, first);
        jdbc.update("UPDATE creator_content_state SET confirmation_id=(SELECT MAX(confirmation_id) FROM creator_content_confirmation WHERE creator_id=?) WHERE creator_id=?", first, first);
        var matches = repository.candidates(List.of("FOOD"), List.of(), taxonomy, config);
        assertThat(matches.get(0).score()).isNull();
        assertThat(matches.get(1).score()).isEqualByComparingTo("0");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void singletonForUpdateSerializesIndependentTransactions() throws Exception {
        var tx = new TransactionTemplate(manager);
        var executor = Executors.newFixedThreadPool(2);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var attempted = new CountDownLatch(1);
        try {
            var holder = executor.submit(() -> tx.execute(status -> {
                repository.ranking(true);
                locked.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("lock release timeout"); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                return true;
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var waiter = executor.submit(() -> tx.execute(status -> {
                attempted.countDown();
                return repository.ranking(true).sequence();
            }));
            assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> waiter.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            assertThat(holder.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(waiter.get(5, TimeUnit.SECONDS)).isNotNegative();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
