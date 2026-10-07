package kr.co.cking.interest.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.common.exception.ErrorCode;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.interest.application.InterestRecommendationResultService.StoreResult;
import kr.co.cking.interest.application.dto.InterestRecommendationCommand;
import kr.co.cking.interest.application.dto.InterestRecommendationCommand.Candidate;
import kr.co.cking.interest.domain.InterestCategory;
import kr.co.cking.interest.domain.InterestCategoryId;
import kr.co.cking.interest.domain.InterestErrorCode;
import kr.co.cking.interest.domain.InterestTaxonomy;
import kr.co.cking.interest.domain.InterestTaxonomyHash;
import kr.co.cking.interest.domain.InterestTaxonomyHash.Row;
import kr.co.cking.interest.repository.InterestCategoryRepository;
import kr.co.cking.interest.repository.InterestRecommendationCandidateRepository;
import kr.co.cking.interest.repository.InterestRecommendationGenerationRepository;
import kr.co.cking.interest.repository.InterestRecommendationStateRepository;
import kr.co.cking.interest.repository.InterestTaxonomyRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 테스트 전용 분류체계(롤백)와 실제 MySQL로 적재 계약(원자 교체·멱등·거부 시 기존 결과 유지)을 확인한다.
 * 공유 DB의 실제 v0.2 적재 결과와 섞이지 않도록 매 테스트가 자기 분류체계 버전을 만들고 그 범위로만 센다.
 */
@SpringBootTest
@Transactional
class InterestRecommendationResultServiceIntegrationTest {

    private static final String CODE = "SPORTS";
    private static final String METHOD = "INTEREST_M3_V1";
    private static final String MODEL = "BAAI/bge-m3@deepinfra-v1";
    private static final String FIRST_HASH = "a".repeat(64);
    private static final String SECOND_HASH = "b".repeat(64);

    @Autowired InterestRecommendationResultService service;
    @Autowired InterestTaxonomyRepository taxonomyRepository;
    @Autowired InterestCategoryRepository categoryRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired InterestRecommendationGenerationRepository generationRepository;
    @Autowired InterestRecommendationCandidateRepository candidateRepository;
    @Autowired InterestRecommendationStateRepository stateRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired CreatorRepository creatorRepository;

    private String version;
    private String taxonomyHash;
    private Creator first;
    private Creator second;

    @BeforeEach
    void setUp() {
        version = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        taxonomyHash = InterestTaxonomyHash.compute(List.of(new Row(CODE, "스포츠", "설명")));
        taxonomyRepository.saveAndFlush(new InterestTaxonomy(version, taxonomyHash, false, Instant.now()));
        categoryRepository.saveAndFlush(new InterestCategory(version, CODE, "스포츠", "설명", 1, true));
        first = creator("a");
        second = creator("b");
    }

    @Test
    void 완결된_묶음을_새_세대로_저장하고_포인터를_만든다() {
        StoreResult result = service.replace(CODE, command(FIRST_HASH, candidate(first, "0.90000000", 1, FIRST_HASH),
                candidate(second, "0.80000000", 2, FIRST_HASH)));

        assertThat(result.applied()).isTrue();
        assertThat(result.candidateCount()).isEqualTo(2);
        assertThat(result.taxonomyVersion()).isEqualTo(version);
        assertThat(result.interestCode()).isEqualTo(CODE);
        assertThat(stateRepository.findById(new InterestCategoryId(version, CODE)).orElseThrow()
                .getCurrentGenerationId()).isEqualTo(result.generationId());
        assertThat(candidateRepository.findByGenerationIdOrderByRankAsc(result.generationId()))
                .extracting(c -> c.getCreatorId(), c -> c.getRank())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(first.getCreatorId(), 1),
                        org.assertj.core.groups.Tuple.tuple(second.getCreatorId(), 2));
    }

    @Test
    void 같은_입력과_같은_내용의_재전송은_새_세대를_만들지_않는다() {
        InterestRecommendationCommand command = command(FIRST_HASH, candidate(first, "0.90000000", 1, FIRST_HASH));
        StoreResult original = service.replace(CODE, command);

        StoreResult replay = service.replace(CODE, command);

        assertThat(replay.applied()).isFalse();
        assertThat(replay.generationId()).isEqualTo(original.generationId());
        assertThat(generations()).isEqualTo(1);
        assertThat(candidates()).isEqualTo(1);
    }

    @Test
    void 같은_입력_해시에_다른_내용은_충돌이고_기존_결과를_유지한다() {
        StoreResult original = service.replace(CODE, command(FIRST_HASH, candidate(first, "0.90000000", 1, FIRST_HASH)));

        assertError(InterestErrorCode.RECOMMENDATION_INPUT_CONFLICT, () -> service.replace(CODE,
                command(FIRST_HASH, candidate(second, "0.90000000", 1, FIRST_HASH))));
        assertError(InterestErrorCode.RECOMMENDATION_INPUT_CONFLICT, () -> service.replace(CODE,
                command(FIRST_HASH, candidate(first, "0.70000000", 1, FIRST_HASH))));

        assertThat(currentGenerationId()).isEqualTo(original.generationId());
        assertThat(generations()).isEqualTo(1);
    }

    @Test
    void 새_입력_해시는_세대를_교체하고_과거_세대는_보존한다() {
        StoreResult original = service.replace(CODE, command(FIRST_HASH, candidate(first, "0.90000000", 1, FIRST_HASH)));

        StoreResult replaced = service.replace(CODE, command(SECOND_HASH, candidate(second, "0.95000000", 1, SECOND_HASH)));

        assertThat(replaced.applied()).isTrue();
        assertThat(currentGenerationId()).isEqualTo(replaced.generationId()).isNotEqualTo(original.generationId());
        assertThat(generations()).isEqualTo(2);
        assertThat(candidateRepository.findByGenerationIdOrderByRankAsc(original.generationId())).hasSize(1);
    }

    @Test
    void 교체된_과거_입력_해시는_재활성화하지_않는다() {
        service.replace(CODE, command(FIRST_HASH, candidate(first, "0.90000000", 1, FIRST_HASH)));
        StoreResult current = service.replace(CODE, command(SECOND_HASH, candidate(second, "0.95000000", 1, SECOND_HASH)));

        assertError(InterestErrorCode.STALE_RECOMMENDATION_INPUT, () -> service.replace(CODE,
                command(FIRST_HASH, candidate(first, "0.90000000", 1, FIRST_HASH))));

        assertThat(currentGenerationId()).isEqualTo(current.generationId());
    }

    @Test
    void 빈_후보_묶음도_정상_세대로_저장해_이전_추천을_비운다() {
        service.replace(CODE, command(FIRST_HASH, candidate(first, "0.90000000", 1, FIRST_HASH)));

        StoreResult empty = service.replace(CODE, command(SECOND_HASH));
        StoreResult replay = service.replace(CODE, command(SECOND_HASH));

        assertThat(empty.applied()).isTrue();
        assertThat(empty.candidateCount()).isZero();
        assertThat(currentGenerationId()).isEqualTo(empty.generationId());
        assertThat(candidateRepository.findByGenerationIdOrderByRankAsc(empty.generationId())).isEmpty();
        assertThat(replay.applied()).isFalse();
        assertThat(replay.generationId()).isEqualTo(empty.generationId());
    }

    @Test
    void taxonomyHash가_등록된_해시와_다르면_거부한다() {
        InterestRecommendationCommand wrongHash = new InterestRecommendationCommand(1L,
                version, "c".repeat(64), CODE, METHOD, MODEL, FIRST_HASH, List.of());

        assertError(InterestErrorCode.INVALID_RECOMMENDATION_RESULT, () -> service.replace(CODE, wrongHash));
        assertThat(generations()).isZero();
    }

    @Test
    void 등록되지_않은_분류체계_버전은_거부한다() {
        InterestRecommendationCommand unknownVersion = new InterestRecommendationCommand(1L,
                "v9.9", taxonomyHash, CODE, METHOD, MODEL, FIRST_HASH, List.of());

        assertError(InterestErrorCode.INVALID_RECOMMENDATION_RESULT, () -> service.replace(CODE, unknownVersion));
    }

    @Test
    void 그_버전에_없는_분야는_RESOURCE_NOT_FOUND다() {
        InterestRecommendationCommand unknownCode = new InterestRecommendationCommand(1L,
                version, taxonomyHash, "NOT_A_CATEGORY", METHOD, MODEL, FIRST_HASH, List.of());

        assertError(CommonErrorCode.RESOURCE_NOT_FOUND, () -> service.replace("NOT_A_CATEGORY", unknownCode));
    }

    @Test
    void 없는_후보_Creator가_하나라도_있으면_묶음_전체를_거부하고_기존_결과를_유지한다() {
        StoreResult original = service.replace(CODE, command(FIRST_HASH, candidate(first, "0.90000000", 1, FIRST_HASH)));
        Candidate missing = new Candidate(
                CODE, Long.MAX_VALUE, new BigDecimal("0.50000000"), 2, METHOD, MODEL, SECOND_HASH);

        assertError(CommonErrorCode.RESOURCE_NOT_FOUND, () -> service.replace(CODE,
                command(SECOND_HASH, candidate(second, "0.90000000", 1, SECOND_HASH), missing)));

        assertThat(currentGenerationId()).isEqualTo(original.generationId());
        assertThat(generations()).isEqualTo(1);
    }

    @Test
    void 형식이_잘못된_묶음은_거부하고_아무것도_저장하지_않는다() {
        assertError(InterestErrorCode.INVALID_RECOMMENDATION_RESULT, () -> service.replace(CODE,
                command(FIRST_HASH, candidate(first, "0.10000000", 1, FIRST_HASH),
                        candidate(second, "0.90000000", 2, FIRST_HASH))));

        assertThat(generations()).isZero();
        assertThat(states()).isZero();
    }

    @Test
    void 새_실행_A_복귀_후_지연_B와_미적용_과거_실행을_거부한다() {
        var a = command(FIRST_HASH, candidate(first, "0.9", 1, FIRST_HASH));
        var b = command(SECOND_HASH, candidate(second, "0.8", 1, SECOND_HASH));
        service.replace(CODE, a);
        service.replace(CODE, b);
        var revert = new InterestRecommendationCommand(4L, version, taxonomyHash, CODE, METHOD, MODEL,
                FIRST_HASH, a.candidates());
        var result = service.replace(CODE, revert);
        assertThat(result.applied()).isTrue();
        assertThat(service.replace(CODE, revert).applied()).isFalse();
        assertError(InterestErrorCode.STALE_RECOMMENDATION_INPUT, () -> service.replace(CODE, b));
        assertError(InterestErrorCode.STALE_RECOMMENDATION_INPUT, () -> service.replace(CODE,
                new InterestRecommendationCommand(3L, version, taxonomyHash, CODE, METHOD, MODEL,
                        SECOND_HASH, b.candidates())));
        assertError(InterestErrorCode.RECOMMENDATION_INPUT_CONFLICT, () -> service.replace(CODE,
                new InterestRecommendationCommand(4L, version, taxonomyHash, CODE, METHOD, MODEL,
                        SECOND_HASH, b.candidates())));
        assertThat(currentGenerationId()).isEqualTo(result.generationId());
        assertThat(generations()).isEqualTo(3);
    }

    private int generations() {
        return jdbcTemplate.queryForObject(
                "select count(*) from interest_recommendation_generation where taxonomy_version = ?", Integer.class, version);
    }

    private int candidates() {
        return jdbcTemplate.queryForObject(
                "select count(*) from interest_recommendation_candidate c join interest_recommendation_generation g "
                        + "on g.generation_id = c.generation_id where g.taxonomy_version = ?", Integer.class, version);
    }

    private int states() {
        return jdbcTemplate.queryForObject(
                "select count(*) from interest_recommendation_state where taxonomy_version = ?", Integer.class, version);
    }

    private Long currentGenerationId() {
        return stateRepository.findById(new InterestCategoryId(version, CODE)).orElseThrow().getCurrentGenerationId();
    }

    private InterestRecommendationCommand command(String inputHash, Candidate... candidates) {
        return new InterestRecommendationCommand(FIRST_HASH.equals(inputHash) ? 1L : 2L,
                version, taxonomyHash, CODE, METHOD, MODEL, inputHash, List.of(candidates));
    }

    private Candidate candidate(Creator creator, String score, int rank, String inputHash) {
        return new Candidate(CODE, creator.getCreatorId(), new BigDecimal(score), rank, METHOD, MODEL, inputHash);
    }

    private void assertError(ErrorCode expected, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(expected));
    }

    private Creator creator(String prefix) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Member owner = memberRepository.saveAndFlush(new Member("owner-" + prefix + "-" + suffix, null, null, MemberRole.USER));
        return creatorRepository.saveAndFlush(new Creator(owner.getMemberId(), prefix + "-" + suffix));
    }
}
