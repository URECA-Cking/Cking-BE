package kr.co.cking.creator.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.creator.application.dto.CreatorSimilarityResultCommand;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.domain.CreatorSimilarityCandidate;
import kr.co.cking.creator.domain.CreatorSimilarityGeneration;
import kr.co.cking.creator.domain.CreatorSimilarityState;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.creator.repository.CreatorSimilarityCandidateRepository;
import kr.co.cking.creator.repository.CreatorSimilarityGenerationRepository;
import kr.co.cking.creator.repository.CreatorSimilarityStateRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class CreatorSimilarityResultServiceTest {

    private static final String FIRST_HASH = "a".repeat(64);

    @Mock MemberRepository memberRepository;
    @Mock CreatorRepository creatorRepository;
    @Mock CreatorSimilarityGenerationRepository generationRepository;
    @Mock CreatorSimilarityCandidateRepository candidateRepository;
    @Mock CreatorSimilarityStateRepository stateRepository;

    @InjectMocks CreatorSimilarityResultService service;

    @BeforeEach
    void setUp() {
        given(memberRepository.findById(1L))
                .willReturn(Optional.of(new Member("admin", null, null, MemberRole.ADMIN)));
        given(creatorRepository.findByIdForUpdate(10L))
                .willReturn(Optional.of(creator(10L, "원본")));
    }

    @Test
    void 완결된_후보_묶음을_새_세대로_저장하고_활성화한다() {
        Creator candidate = creator(20L, "후보");
        given(creatorRepository.findByCreatorIdIn(Set.of(20L))).willReturn(List.of(candidate));
        given(stateRepository.findByCreatorIdForUpdate(10L)).willReturn(Optional.empty());
        given(generationRepository.findByCreatorIdAndInputHashForUpdate(10L, FIRST_HASH))
                .willReturn(Optional.empty());
        given(generationRepository.saveAndFlush(any())).willAnswer(invocation -> {
            CreatorSimilarityGeneration generation = invocation.getArgument(0);
            ReflectionTestUtils.setField(generation, "generationId", 100L);
            return generation;
        });

        CreatorSimilarityResultService.StoreResult result = service.replace(1L, 10L, command(
                FIRST_HASH, candidate(10L, 20L, "0.90000000", 1)));

        assertThat(result.applied()).isTrue();
        assertThat(result.generationId()).isEqualTo(100L);
        assertThat(result.candidateCount()).isEqualTo(1);
        then(candidateRepository).should().saveAll(any());
        then(stateRepository).should().save(any(CreatorSimilarityState.class));
    }

    @Test
    void 현재_입력_해시와_같은_묶음은_중복_적재하지_않는다() {
        Creator candidate = creator(20L, "후보");
        CreatorSimilarityGeneration generation = generation(100L, FIRST_HASH);
        given(creatorRepository.findByCreatorIdIn(Set.of(20L))).willReturn(List.of(candidate));
        given(stateRepository.findByCreatorIdForUpdate(10L))
                .willReturn(Optional.of(new CreatorSimilarityState(10L, 100L)));
        given(generationRepository.findByCreatorIdAndInputHashForUpdate(10L, FIRST_HASH))
                .willReturn(Optional.of(generation));
        given(candidateRepository.findByGenerationIdForUpdateOrderByRankAsc(100L))
                .willReturn(List.of(new CreatorSimilarityCandidate(
                        100L, 20L, new BigDecimal("0.90000000"), 1)));

        CreatorSimilarityResultService.StoreResult result = service.replace(1L, 10L, command(
                FIRST_HASH, candidate(10L, 20L, "0.90000000", 1)));

        assertThat(result.applied()).isFalse();
        then(generationRepository).should(never()).saveAndFlush(any());
        then(candidateRepository).should(never()).saveAll(any());
    }

    @Test
    void 이미_교체된_과거_입력_해시는_거부한다() {
        given(creatorRepository.findByCreatorIdIn(Set.of(20L))).willReturn(List.of(creator(20L, "후보")));
        given(stateRepository.findByCreatorIdForUpdate(10L))
                .willReturn(Optional.of(new CreatorSimilarityState(10L, 200L)));
        given(generationRepository.findByCreatorIdAndInputHashForUpdate(10L, FIRST_HASH))
                .willReturn(Optional.of(generation(100L, FIRST_HASH)));

        assertThatThrownBy(() -> service.replace(1L, 10L, command(
                FIRST_HASH, candidate(10L, 20L, "0.90000000", 1))))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CreatorErrorCode.STALE_RECOMMENDATION_INPUT));
    }

    @Test
    void 자기_자신이나_중복_후보는_묶음_전체를_거부한다() {
        assertThatThrownBy(() -> service.replace(1L, 10L, command(
                FIRST_HASH, candidate(10L, 10L, "0.90000000", 1))))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CreatorErrorCode.INVALID_RECOMMENDATION_RESULT));

        assertThatThrownBy(() -> service.replace(1L, 10L, command(
                FIRST_HASH,
                candidate(10L, 20L, "0.90000000", 1),
                candidate(10L, 20L, "0.80000000", 2))))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CreatorErrorCode.INVALID_RECOMMENDATION_RESULT));

        then(generationRepository).should(never()).saveAndFlush(any());
    }

    @Test
    void 없는_후보_크리에이터가_섞이면_전체를_거부한다() {
        given(creatorRepository.findByCreatorIdIn(Set.of(20L))).willReturn(List.of());

        assertThatThrownBy(() -> service.replace(1L, 10L, command(
                FIRST_HASH, candidate(10L, 20L, "0.90000000", 1))))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));

        then(generationRepository).should(never()).saveAndFlush(any());
    }

    @Test
    void 점수_내림차순과_동점_creatorId_오름차순이_아니면_거부한다() {
        assertThatThrownBy(() -> service.replace(1L, 10L, command(
                FIRST_HASH,
                candidate(10L, 30L, "0.90000000", 1),
                candidate(10L, 20L, "0.90000000", 2))))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CreatorErrorCode.INVALID_RECOMMENDATION_RESULT));
    }

    @Test
    void 생성_메타데이터가_있는_빈_묶음은_새_세대로_저장하고_활성화한다() {
        CreatorSimilarityState state = new CreatorSimilarityState(10L, 99L);
        given(stateRepository.findByCreatorIdForUpdate(10L)).willReturn(Optional.of(state));
        given(generationRepository.findByCreatorIdAndInputHashForUpdate(10L, FIRST_HASH))
                .willReturn(Optional.empty());
        given(generationRepository.saveAndFlush(any())).willAnswer(invocation -> {
            CreatorSimilarityGeneration generation = invocation.getArgument(0);
            ReflectionTestUtils.setField(generation, "generationId", 100L);
            return generation;
        });

        CreatorSimilarityResultService.StoreResult result = service.replace(
                1L,
                10L,
                new CreatorSimilarityResultCommand(10L, "M4", "model-v1", FIRST_HASH, List.of()));

        assertThat(result.applied()).isTrue();
        assertThat(result.candidateCount()).isZero();
        assertThat(state.getCurrentGenerationId()).isEqualTo(100L);
        then(creatorRepository).should(never()).findByCreatorIdIn(any());
    }

    @Test
    void 빈_묶음에_생성_메타데이터가_없으면_거부한다() {
        assertThatThrownBy(() -> service.replace(
                1L, 10L, new CreatorSimilarityResultCommand(10L, null, null, null, List.of())))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                                CreatorErrorCode.INVALID_RECOMMENDATION_RESULT));

        then(generationRepository).should(never()).saveAndFlush(any());
    }

    @Test
    void 최상위와_후보의_생성_메타데이터가_다르면_거부한다() {
        CreatorSimilarityResultCommand command = new CreatorSimilarityResultCommand(
                10L,
                "M2",
                "model-v1",
                FIRST_HASH,
                List.of(candidate(10L, 20L, "0.90000000", 1)));

        assertThatThrownBy(() -> service.replace(1L, 10L, command))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                                CreatorErrorCode.INVALID_RECOMMENDATION_RESULT));

        then(generationRepository).should(never()).saveAndFlush(any());
    }

    @Test
    void 최상위와_후보의_생성_메타데이터가_같으면_저장한다() {
        given(creatorRepository.findByCreatorIdIn(Set.of(20L)))
                .willReturn(List.of(creator(20L, "후보")));
        given(stateRepository.findByCreatorIdForUpdate(10L)).willReturn(Optional.empty());
        given(generationRepository.findByCreatorIdAndInputHashForUpdate(10L, FIRST_HASH))
                .willReturn(Optional.empty());
        given(generationRepository.saveAndFlush(any())).willAnswer(invocation -> {
            CreatorSimilarityGeneration generation = invocation.getArgument(0);
            ReflectionTestUtils.setField(generation, "generationId", 100L);
            return generation;
        });
        CreatorSimilarityResultCommand command = new CreatorSimilarityResultCommand(
                10L,
                "M4",
                "model-v1",
                FIRST_HASH,
                List.of(candidate(10L, 20L, "0.90000000", 1)));

        CreatorSimilarityResultService.StoreResult result = service.replace(1L, 10L, command);

        assertThat(result.applied()).isTrue();
        assertThat(result.candidateCount()).isEqualTo(1);
        then(generationRepository).should().saveAndFlush(any());
    }

    private CreatorSimilarityResultCommand command(
            String inputHash,
            CreatorSimilarityResultCommand.Candidate... candidates
    ) {
        return new CreatorSimilarityResultCommand(10L, null, null, null, List.of(candidates));
    }

    private CreatorSimilarityResultCommand.Candidate candidate(
            Long creatorId,
            Long similarCreatorId,
            String score,
            int rank
    ) {
        return new CreatorSimilarityResultCommand.Candidate(
                creatorId, similarCreatorId, new BigDecimal(score), rank, "M4", "model-v1", FIRST_HASH);
    }

    private Creator creator(Long creatorId, String name) {
        Creator creator = new Creator(creatorId + 1000, name);
        ReflectionTestUtils.setField(creator, "creatorId", creatorId);
        return creator;
    }

    private CreatorSimilarityGeneration generation(Long generationId, String inputHash) {
        CreatorSimilarityGeneration generation = new CreatorSimilarityGeneration(
                10L, "M4", "model-v1", inputHash, Instant.parse("2026-10-02T00:00:00Z"));
        ReflectionTestUtils.setField(generation, "generationId", generationId);
        return generation;
    }
}
