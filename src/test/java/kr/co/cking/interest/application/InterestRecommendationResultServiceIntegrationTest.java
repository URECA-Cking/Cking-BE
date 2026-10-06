package kr.co.cking.interest.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
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
import kr.co.cking.interest.domain.InterestCategoryId;
import kr.co.cking.interest.domain.InterestErrorCode;
import kr.co.cking.interest.repository.InterestRecommendationCandidateRepository;
import kr.co.cking.interest.repository.InterestRecommendationGenerationRepository;
import kr.co.cking.interest.repository.InterestRecommendationStateRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/** 시드된 v0.2 분류체계와 실제 MySQL로 적재 계약(원자 교체·멱등·거부 시 기존 결과 유지)을 확인한다. 테스트마다 롤백한다. */
@SpringBootTest
@Transactional
class InterestRecommendationResultServiceIntegrationTest {

    private static final String VERSION = "v0.2";
    private static final String CODE = "SPORTS";
    private static final String METHOD = "INTEREST_M3_V1";
    private static final String MODEL = "BAAI/bge-m3@deepinfra-v1";
    private static final String FIRST_HASH = "a".repeat(64);
    private static final String SECOND_HASH = "b".repeat(64);

    @Autowired InterestRecommendationResultService service;
    @Autowired InterestRecommendationGenerationRepository generationRepository;
    @Autowired InterestRecommendationCandidateRepository candidateRepository;
    @Autowired InterestRecommendationStateRepository stateRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired CreatorRepository creatorRepository;

    private String taxonomyHash;
    private Creator first;
    private Creator second;

    @BeforeEach
    void setUp() throws Exception {
        taxonomyHash = Files.readString(Path.of("src/test/resources/fixtures/taxonomy/v02.sha256.txt")).strip();
        first = creator("a");
        second = creator("b");
    }

    @Test
    void 완결된_묶음을_새_세대로_저장하고_포인터를_만든다() {
        StoreResult result = service.replace(CODE, command(FIRST_HASH, candidate(first, "0.90000000", 1, FIRST_HASH),
                candidate(second, "0.80000000", 2, FIRST_HASH)));

        assertThat(result.applied()).isTrue();
        assertThat(result.candidateCount()).isEqualTo(2);
        assertThat(result.taxonomyVersion()).isEqualTo(VERSION);
        assertThat(result.interestCode()).isEqualTo(CODE);
        assertThat(stateRepository.findById(new InterestCategoryId(VERSION, CODE)).orElseThrow()
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
        assertThat(generationRepository.count()).isEqualTo(1);
        assertThat(candidateRepository.count()).isEqualTo(1);
    }

    @Test
    void 같은_입력_해시에_다른_내용은_충돌이고_기존_결과를_유지한다() {
        StoreResult original = service.replace(CODE, command(FIRST_HASH, candidate(first, "0.90000000", 1, FIRST_HASH)));

        assertError(InterestErrorCode.RECOMMENDATION_INPUT_CONFLICT, () -> service.replace(CODE,
                command(FIRST_HASH, candidate(second, "0.90000000", 1, FIRST_HASH))));
        assertError(InterestErrorCode.RECOMMENDATION_INPUT_CONFLICT, () -> service.replace(CODE,
                command(FIRST_HASH, candidate(first, "0.70000000", 1, FIRST_HASH))));

        assertThat(currentGenerationId()).isEqualTo(original.generationId());
        assertThat(generationRepository.count()).isEqualTo(1);
    }

    @Test
    void 새_입력_해시는_세대를_교체하고_과거_세대는_보존한다() {
        StoreResult original = service.replace(CODE, command(FIRST_HASH, candidate(first, "0.90000000", 1, FIRST_HASH)));

        StoreResult replaced = service.replace(CODE, command(SECOND_HASH, candidate(second, "0.95000000", 1, SECOND_HASH)));

        assertThat(replaced.applied()).isTrue();
        assertThat(currentGenerationId()).isEqualTo(replaced.generationId()).isNotEqualTo(original.generationId());
        assertThat(generationRepository.count()).isEqualTo(2);
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
        InterestRecommendationCommand wrongHash = new InterestRecommendationCommand(
                VERSION, "c".repeat(64), CODE, METHOD, MODEL, FIRST_HASH, List.of());

        assertError(InterestErrorCode.INVALID_RECOMMENDATION_RESULT, () -> service.replace(CODE, wrongHash));
        assertThat(generationRepository.count()).isZero();
    }

    @Test
    void 등록되지_않은_분류체계_버전은_거부한다() {
        InterestRecommendationCommand unknownVersion = new InterestRecommendationCommand(
                "v9.9", taxonomyHash, CODE, METHOD, MODEL, FIRST_HASH, List.of());

        assertError(InterestErrorCode.INVALID_RECOMMENDATION_RESULT, () -> service.replace(CODE, unknownVersion));
    }

    @Test
    void 그_버전에_없는_분야는_RESOURCE_NOT_FOUND다() {
        InterestRecommendationCommand unknownCode = new InterestRecommendationCommand(
                VERSION, taxonomyHash, "NOT_A_CATEGORY", METHOD, MODEL, FIRST_HASH, List.of());

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
        assertThat(generationRepository.count()).isEqualTo(1);
    }

    @Test
    void 형식이_잘못된_묶음은_거부하고_아무것도_저장하지_않는다() {
        assertError(InterestErrorCode.INVALID_RECOMMENDATION_RESULT, () -> service.replace(CODE,
                command(FIRST_HASH, candidate(first, "0.10000000", 1, FIRST_HASH),
                        candidate(second, "0.90000000", 2, FIRST_HASH))));

        assertThat(generationRepository.count()).isZero();
        assertThat(stateRepository.count()).isZero();
    }

    private Long currentGenerationId() {
        return stateRepository.findById(new InterestCategoryId(VERSION, CODE)).orElseThrow().getCurrentGenerationId();
    }

    private InterestRecommendationCommand command(String inputHash, Candidate... candidates) {
        return new InterestRecommendationCommand(
                VERSION, taxonomyHash, CODE, METHOD, MODEL, inputHash, List.of(candidates));
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
