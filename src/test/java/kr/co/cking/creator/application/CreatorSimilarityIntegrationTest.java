package kr.co.cking.creator.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.application.dto.CreatorSimilarityResultCommand;
import kr.co.cking.creator.application.dto.CreatorSimilarityView;
import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 MySQL에서 추천 세대 이력과 현재 포인터의 원자 교체·멱등·과거 해시 차단을 검증한다. */
@SpringBootTest
class CreatorSimilarityIntegrationTest {

    private static final String FIRST_HASH = "a".repeat(64);
    private static final String SECOND_HASH = "b".repeat(64);

    @Autowired CreatorSimilarityResultService resultService;
    @Autowired CreatorSimilarityQueryService queryService;
    @Autowired CreatorRepository creatorRepository;
    @Autowired MemberRepository memberRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private final List<Member> members = new ArrayList<>();
    private final List<Creator> creators = new ArrayList<>();
    private Creator source;
    private Creator firstCandidate;
    private Creator secondCandidate;

    @BeforeEach
    void setUp() {
        source = creator("source");
        firstCandidate = creator("first");
        secondCandidate = creator("second");
    }

    @AfterEach
    void cleanUp() {
        new SimilarityTestCleaner(jdbcTemplate).delete(members, creators);
        creatorRepository.deleteAll(creators);
        memberRepository.deleteAll(members);
    }

    @Test
    void 새_세대만_공개하고_동일_재전송은_멱등하며_과거_세대_재활성화는_막는다() {
        CreatorSimilarityView empty = queryService.findSimilar(source.getCreatorId(), 5);
        assertThat(empty.candidates()).isEmpty();

        CreatorSimilarityResultService.StoreResult first = resultService.replace(
                source.getCreatorId(), command(FIRST_HASH, firstCandidate, "0.90000000"));
        CreatorSimilarityResultService.StoreResult replay = resultService.replace(
                source.getCreatorId(), command(FIRST_HASH, firstCandidate, "0.90000000"));

        assertThat(first.applied()).isTrue();
        assertThat(replay.applied()).isFalse();
        assertThat(replay.generationId()).isEqualTo(first.generationId());
        assertThat(cleaner().generationCount(source.getCreatorId())).isEqualTo(1);
        assertThat(queryService.findSimilar(source.getCreatorId(), 5).candidates())
                .extracting(CreatorSimilarityView.Candidate::similarCreatorId)
                .containsExactly(firstCandidate.getCreatorId());

        CreatorSimilarityResultService.StoreResult second = resultService.replace(
                source.getCreatorId(), command(SECOND_HASH, secondCandidate, "0.95000000"));

        assertThat(second.applied()).isTrue();
        assertThat(cleaner().generationCount(source.getCreatorId())).isEqualTo(2);
        assertThat(queryService.findSimilar(source.getCreatorId(), 5).candidates())
                .extracting(CreatorSimilarityView.Candidate::similarCreatorId)
                .containsExactly(secondCandidate.getCreatorId());

        assertThatThrownBy(() -> resultService.replace(
                source.getCreatorId(), command(FIRST_HASH, firstCandidate, "0.90000000")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CreatorErrorCode.STALE_RECOMMENDATION_INPUT));
        assertThat(queryService.findSimilar(source.getCreatorId(), 5).candidates())
                .extracting(CreatorSimilarityView.Candidate::similarCreatorId)
                .containsExactly(secondCandidate.getCreatorId());
    }

    @Test
    void 같은_입력의_동시_적재는_하나의_세대로_수렴한다() throws Exception {
        CreatorSimilarityResultCommand command = command(FIRST_HASH, firstCandidate, "0.90000000");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<CreatorSimilarityResultService.StoreResult> first = executor.submit(() -> {
                start.await();
                return resultService.replace(source.getCreatorId(), command);
            });
            Future<CreatorSimilarityResultService.StoreResult> second = executor.submit(() -> {
                start.await();
                return resultService.replace(source.getCreatorId(), command);
            });
            start.countDown();

            List<CreatorSimilarityResultService.StoreResult> results = List.of(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertThat(results).extracting(CreatorSimilarityResultService.StoreResult::applied)
                    .containsExactlyInAnyOrder(true, false);
            assertThat(results).extracting(CreatorSimilarityResultService.StoreResult::generationId)
                    .containsOnly(results.getFirst().generationId());
            assertThat(cleaner().generationCount(source.getCreatorId())).isEqualTo(1);
            assertThat(cleaner().candidateCount(source.getCreatorId())).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 기존_추천_뒤_빈_결과를_반영하면_공개_후보가_비워진다() {
        resultService.replace(
                source.getCreatorId(), command(FIRST_HASH, firstCandidate, "0.90000000"));

        CreatorSimilarityResultService.StoreResult emptyResult = resultService.replace(
                source.getCreatorId(),
                new CreatorSimilarityResultCommand(
                        source.getCreatorId(), "M4", "model-v2", SECOND_HASH, List.of()));
        CreatorSimilarityResultService.StoreResult replay = resultService.replace(
                source.getCreatorId(),
                new CreatorSimilarityResultCommand(
                        source.getCreatorId(), "M4", "model-v2", SECOND_HASH, List.of()));

        CreatorSimilarityView view = queryService.findSimilar(source.getCreatorId(), 5);
        assertThat(emptyResult.applied()).isTrue();
        assertThat(emptyResult.candidateCount()).isZero();
        assertThat(replay.applied()).isFalse();
        assertThat(replay.generationId()).isEqualTo(emptyResult.generationId());
        assertThat(view.method()).isEqualTo("M4");
        assertThat(view.modelVersion()).isEqualTo("model-v2");
        assertThat(view.inputHash()).isEqualTo(SECOND_HASH);
        assertThat(view.candidates()).isEmpty();
        assertThat(cleaner().generationCount(source.getCreatorId())).isEqualTo(2);
        assertThat(cleaner().candidateCount(source.getCreatorId())).isEqualTo(1);
    }

    private SimilarityTestCleaner cleaner() {
        return new SimilarityTestCleaner(jdbcTemplate);
    }

    private CreatorSimilarityResultCommand command(String hash, Creator candidate, String score) {
        return new CreatorSimilarityResultCommand(source.getCreatorId(), null, null, null, List.of(
                new CreatorSimilarityResultCommand.Candidate(
                        source.getCreatorId(), candidate.getCreatorId(), new BigDecimal(score), 1,
                        "M4", "model-v1", hash)));
    }

    private Member member(String prefix, MemberRole role) {
        Member member = memberRepository.saveAndFlush(
                new Member(prefix + "-" + suffix(), null, null, role));
        members.add(member);
        return member;
    }

    private Creator creator(String prefix) {
        Member owner = member("owner-" + prefix, MemberRole.USER);
        Creator creator = creatorRepository.saveAndFlush(
                new Creator(owner.getMemberId(), prefix + "-" + suffix()));
        creators.add(creator);
        return creator;
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
