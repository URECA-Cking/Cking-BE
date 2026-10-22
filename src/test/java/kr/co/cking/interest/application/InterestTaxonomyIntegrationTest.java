package kr.co.cking.interest.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import kr.co.cking.interest.application.dto.SelectableInterests;
import kr.co.cking.interest.domain.InterestCategory;
import kr.co.cking.interest.domain.InterestTaxonomy;
import kr.co.cking.interest.domain.InterestTaxonomyHash;
import kr.co.cking.interest.domain.InterestTaxonomyHash.Row;
import kr.co.cking.interest.repository.InterestCategoryRepository;
import kr.co.cking.interest.repository.InterestTaxonomyRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** 실제 MySQL로 제약(활성 1개·노출 순서 UNIQUE·해시 CHECK)과 목록 조회·해시 검증을 확인한다. 테스트마다 롤백한다. */
@SpringBootTest
@Transactional
class InterestTaxonomyIntegrationTest {

    @Autowired InterestTaxonomyRepository taxonomyRepository;
    @Autowired InterestCategoryRepository categoryRepository;
    @Autowired InterestQueryService queryService;
    @Autowired InterestTaxonomyVerifier verifier;
    @Autowired JdbcTemplate jdbcTemplate;
    @PersistenceContext EntityManager entityManager;

    @Test
    void 활성_분류체계의_활성_분야만_노출_순서대로_반환한다() {
        deactivateExisting();
        String version = version();
        taxonomyRepository.saveAndFlush(new InterestTaxonomy(version, hash("a"), true, Instant.now()));
        categoryRepository.saveAndFlush(category(version, "FOOD", "요리·푸드", 2, true));
        categoryRepository.saveAndFlush(category(version, "FITNESS", "운동·건강", 1, true));
        categoryRepository.saveAndFlush(category(version, "GAME", "게임", 3, false));
        String otherVersion = version();
        taxonomyRepository.saveAndFlush(new InterestTaxonomy(otherVersion, hash("b"), false, Instant.now()));
        categoryRepository.saveAndFlush(category(otherVersion, "TRAVEL", "여행", 1, true));

        SelectableInterests result = queryService.findSelectable();

        assertThat(result.taxonomyVersion()).isEqualTo(version);
        assertThat(result.maxSelection()).isEqualTo(3);
        assertThat(result.items()).extracting(SelectableInterests.Item::interestCode)
                .containsExactly("FITNESS", "FOOD");
    }

    @Test
    void 활성_분류체계가_없으면_빈_목록이다() {
        deactivateExisting();
        taxonomyRepository.saveAndFlush(new InterestTaxonomy(version(), hash("c"), false, Instant.now()));

        SelectableInterests result = queryService.findSelectable();

        assertThat(result.taxonomyVersion()).isNull();
        assertThat(result.items()).isEmpty();
    }

    @Test
    void 활성_분류체계는_DB_제약으로_하나만_허용한다() {
        deactivateExisting();
        taxonomyRepository.saveAndFlush(new InterestTaxonomy(version(), hash("d"), true, Instant.now()));

        assertThatThrownBy(() -> taxonomyRepository.saveAndFlush(
                new InterestTaxonomy(version(), hash("e"), true, Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 같은_버전에서_노출_순서는_중복될_수_없다() {
        String version = version();
        taxonomyRepository.saveAndFlush(new InterestTaxonomy(version, hash("f"), false, Instant.now()));
        categoryRepository.saveAndFlush(category(version, "FOOD", "요리·푸드", 1, true));

        assertThatThrownBy(() -> categoryRepository.saveAndFlush(category(version, "GAME", "게임", 1, true)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 같은_코드와_노출_순서도_분류체계_버전이_다르면_별개의_분야다() {
        String version = version();
        taxonomyRepository.saveAndFlush(new InterestTaxonomy(version, hash("f"), false, Instant.now()));
        categoryRepository.saveAndFlush(category(version, "FOOD", "요리·푸드", 1, true));
        String otherVersion = version();
        taxonomyRepository.saveAndFlush(new InterestTaxonomy(otherVersion, hash("g"), false, Instant.now()));
        categoryRepository.saveAndFlush(category(otherVersion, "FOOD", "요리·푸드", 1, true));
        assertThat(categoryRepository.findAllByTaxonomyVersion(otherVersion)).hasSize(1);
    }

    @Test
    void 해시는_64자_소문자_hex만_저장한다() {
        assertThatThrownBy(() -> taxonomyRepository.saveAndFlush(
                new InterestTaxonomy(version(), "A".repeat(64), false, Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 저장된_행으로_다시_계산한_해시가_등록된_해시와_같은지_검증한다() {
        String version = version();
        List<Row> rows = List.of(
                new Row("FITNESS", "운동·건강", "헬스, 요가"),
                new Row("FOOD", "요리·푸드", "집밥, 베이킹"));
        taxonomyRepository.saveAndFlush(
                new InterestTaxonomy(version, InterestTaxonomyHash.compute(rows), false, Instant.now()));
        categoryRepository.saveAndFlush(category(version, "FITNESS", "운동·건강", 1, true, "헬스, 요가"));
        categoryRepository.saveAndFlush(category(version, "FOOD", "요리·푸드", 2, false, "집밥, 베이킹"));

        // 비활성 분야도 해시 대상이다.
        assertThat(verifier.matchesRegisteredHash(version)).isTrue();

        jdbcTemplate.update("update interest_category set description = ? where taxonomy_version = ? "
                + "and interest_code = 'FOOD'", "집밥, 베이킹, 바뀜", version);
        // 같은 트랜잭션의 1차 캐시를 비워 DB 값을 다시 읽게 한다.
        entityManager.clear();
        assertThat(verifier.matchesRegisteredHash(version)).isFalse();
    }

    @Test
    void v02_시드는_17개_분야이고_LLM_fixture_해시와_일치한다() throws Exception {
        InterestTaxonomy seeded = taxonomyRepository.findById("v0.2").orElseThrow();

        assertThat(seeded.isActive()).isTrue();
        assertThat(seeded.getTaxonomyHash()).isEqualTo(
                Files.readString(Path.of("src/test/resources/fixtures/taxonomy/v02.sha256.txt")).strip());
        assertThat(categoryRepository.findAllByTaxonomyVersion("v0.2")).hasSize(17)
                .extracting(InterestCategory::getDisplayOrder).containsExactly(
                        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17);
        // 시드 행에서 다시 계산한 해시가 등록된 해시와 같다(= Python 정본과 같은 canonical JSON).
        assertThat(verifier.matchesRegisteredHash("v0.2")).isTrue();
    }

    @Test
    void 등록되지_않은_버전은_해시가_일치하지_않는다() {
        assertThat(verifier.matchesRegisteredHash(version())).isFalse();
    }

    private void deactivateExisting() {
        jdbcTemplate.update("update interest_taxonomy set active = false");
    }

    private InterestCategory category(String version, String code, String name, int order, boolean active) {
        return category(version, code, name, order, active, "설명");
    }

    private InterestCategory category(
            String version, String code, String name, int order, boolean active, String description) {
        return new InterestCategory(version, code, name, description, order, active);
    }

    private String version() {
        return "t" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String hash(String seed) {
        return InterestTaxonomyHash.compute(List.of(new Row(seed, seed, seed)));
    }
}
