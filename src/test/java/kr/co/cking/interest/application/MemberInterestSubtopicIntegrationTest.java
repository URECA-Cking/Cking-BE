package kr.co.cking.interest.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.interest.application.dto.MemberInterests;
import kr.co.cking.interest.application.dto.SelectableInterests;
import kr.co.cking.interest.domain.InterestSubtopicHash;
import kr.co.cking.interest.domain.InterestSubtopicHash.Row;
import kr.co.cking.interest.repository.InterestSubtopicRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** 시드된 `subtopic-v1`(85개)로 세부 주제의 조회·저장 규칙을 실제 MySQL에서 확인한다. 테스트마다 롤백한다. */
@SpringBootTest
@Transactional
class MemberInterestSubtopicIntegrationTest {

    private static final String V = "subtopic-v1";

    @Autowired MemberInterestService service;
    @Autowired InterestQueryService queryService;
    @Autowired MemberRepository memberRepository;
    @Autowired InterestSubtopicRepository subtopicRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @PersistenceContext EntityManager entityManager;

    private Long memberId;

    @BeforeEach
    void setUp() {
        memberId = memberRepository.saveAndFlush(
                new Member("세부주제테스트-" + UUID.randomUUID().toString().substring(0, 8), null, null, MemberRole.USER))
                .getMemberId();
    }

    @Test
    void 시드는_85개이고_저장된_행에서_다시_계산한_해시가_등록_해시와_fixture와_같다() throws Exception {
        List<Row> rows = subtopicRepository.findAllBySubtopicVersion(V).stream()
                .map(s -> new Row(s.getSubtopicCode(), s.getParentInterestCode(), s.getName(), s.getDescription()))
                .toList();
        String registered = jdbcTemplate.queryForObject(
                "select subtopic_hash from interest_subtopic_set where subtopic_version = ?", String.class, V);
        String fixture = Files.readString(
                Path.of("src/test/resources/fixtures/taxonomy/subtopic-v1.sha256.txt"), StandardCharsets.UTF_8).strip();

        assertThat(rows).hasSize(85);
        assertThat(InterestSubtopicHash.compute(rows)).isEqualTo(registered).isEqualTo(fixture);
    }

    @Test
    void 선택_가능_목록은_세부_주제를_상위_아래에_노출_순서로_내려준다() {
        SelectableInterests result = queryService.findSelectable();

        assertThat(result.subtopicVersion()).isEqualTo(V);
        assertThat(result.maxSubtopicPerParent()).isEqualTo(3);
        assertThat(result.items()).hasSize(17);
        assertThat(result.items().stream().mapToInt(item -> item.subtopics().size()).sum()).isEqualTo(85);
        SelectableInterests.Item fitness = result.items().get(0);
        assertThat(fitness.interestCode()).isEqualTo("FITNESS");
        assertThat(fitness.subtopics()).extracting(SelectableInterests.Subtopic::subtopicCode)
                .startsWith("FITNESS_WEIGHTS", "FITNESS_HOME_WORKOUT");
    }

    @Test
    void 활성_세부_세트가_없으면_세부_목록만_비고_200_구성이다() {
        jdbcTemplate.update("update interest_subtopic_set set active = false");

        SelectableInterests result = queryService.findSelectable();

        assertThat(result.taxonomyVersion()).isEqualTo("v0.2");
        assertThat(result.subtopicVersion()).isNull();
        assertThat(result.items()).hasSize(17).allSatisfy(item -> assertThat(item.subtopics()).isEmpty());
        assertThat(service.findMine(memberId).subtopicVersion()).isNull();
    }

    @Test
    void 선택이_없으면_활성_세트_버전과_빈_배열을_반환한다() {
        MemberInterests mine = service.findMine(memberId);

        assertThat(mine.subtopicVersion()).isEqualTo(V);
        assertThat(mine.subtopicCodes()).isEmpty();
    }

    @Test
    void 세부_주제를_저장하면_세트_노출_순서로_반환한다() {
        MemberInterests saved = service.replace(memberId, "v0.2", List.of("FITNESS", "FOOD"),
                V, List.of("FOOD_BAKING", "FITNESS_YOGA", "FITNESS_WEIGHTS"));

        assertThat(saved.subtopicVersion()).isEqualTo(V);
        assertThat(saved.subtopicCodes()).containsExactly("FITNESS_WEIGHTS", "FITNESS_YOGA", "FOOD_BAKING");
        assertThat(service.findMine(memberId).subtopicCodes())
                .containsExactly("FITNESS_WEIGHTS", "FITNESS_YOGA", "FOOD_BAKING");
    }

    @Test
    void 세부_필드를_모두_생략하면_기존_세부_선택을_유지한다() {
        service.replace(memberId, "v0.2", List.of("FITNESS"), V, List.of("FITNESS_YOGA"));

        MemberInterests kept = service.replace(memberId, "v0.2", List.of("FITNESS", "FOOD"));

        assertThat(kept.interestCodes()).containsExactly("FITNESS", "FOOD");
        assertThat(kept.subtopicCodes()).containsExactly("FITNESS_YOGA");
    }

    @Test
    void 세부_필드를_보내면_빈_목록을_포함해_전체를_교체한다() {
        service.replace(memberId, "v0.2", List.of("FITNESS", "FOOD"), V, List.of("FITNESS_YOGA", "FOOD_BAKING"));

        MemberInterests swapped = service.replace(
                memberId, "v0.2", List.of("FITNESS", "FOOD"), V, List.of("FOOD_DESSERT"));
        MemberInterests cleared = service.replace(memberId, "v0.2", List.of("FITNESS", "FOOD"), V, List.of());

        assertThat(swapped.subtopicCodes()).containsExactly("FOOD_DESSERT");
        assertThat(cleared.subtopicCodes()).isEmpty();
        assertThat(cleared.subtopicVersion()).isEqualTo(V);
    }

    @Test
    void 상위를_해제하면_그_아래_세부_선택이_같은_저장에서_삭제된다() {
        service.replace(memberId, "v0.2", List.of("FITNESS", "FOOD"), V, List.of("FITNESS_YOGA", "FOOD_BAKING"));

        MemberInterests omitted = service.replace(memberId, "v0.2", List.of("FOOD"));
        assertThat(omitted.subtopicCodes()).containsExactly("FOOD_BAKING");

        MemberInterests all = service.replace(memberId, "v0.2", List.of());
        assertThat(all.subtopicCodes()).isEmpty();
        assertThat(rowCount()).isZero();
    }

    @Test
    void 유지되는_세부_선택의_시각은_바뀌지_않는다() {
        service.replace(memberId, "v0.2", List.of("FITNESS"), V, List.of("FITNESS_YOGA"));
        Instant old = Instant.parse("2026-01-01T00:00:00Z");
        jdbcTemplate.update("update member_interest_subtopic set selected_at = ? where member_id = ?",
                Timestamp.from(old), memberId);
        entityManager.clear();

        service.replace(memberId, "v0.2", List.of("FITNESS"), V, List.of("FITNESS_YOGA", "FITNESS_PILATES"));
        entityManager.flush();
        entityManager.clear();

        assertThat(selectedAt("FITNESS_YOGA")).isEqualTo(old);
        assertThat(selectedAt("FITNESS_PILATES")).isAfter(old);
    }

    @Test
    void 규칙을_어긴_요청은_거부하고_기존_선택을_유지한다() {
        service.replace(memberId, "v0.2", List.of("FITNESS", "FOOD"), V, List.of("FITNESS_YOGA"));

        // 상위당 4개
        assertInvalid(List.of("FITNESS"), V, List.of(
                "FITNESS_WEIGHTS", "FITNESS_HOME_WORKOUT", "FITNESS_YOGA", "FITNESS_PILATES"));
        // 부모 미선택
        assertInvalid(List.of("FITNESS"), V, List.of("FOOD_BAKING"));
        // 중복 / null / blank
        assertInvalid(List.of("FITNESS"), V, List.of("FITNESS_YOGA", "FITNESS_YOGA"));
        assertInvalid(List.of("FITNESS"), V, Arrays.asList("FITNESS_YOGA", null));
        assertInvalid(List.of("FITNESS"), V, List.of(" "));
        // 없는 코드·버전, 한쪽 필드만
        assertInvalid(List.of("FITNESS"), V, List.of("NOT_A_SUBTOPIC"));
        assertInvalid(List.of("FITNESS"), "subtopic-v9", List.of("FITNESS_YOGA"));
        assertInvalid(List.of("FITNESS"), V, null);
        assertInvalid(List.of("FITNESS"), null, List.of("FITNESS_YOGA"));
        assertInvalid(List.of("FITNESS"), "", List.of());

        MemberInterests mine = service.findMine(memberId);
        assertThat(mine.interestCodes()).containsExactly("FITNESS", "FOOD");
        assertThat(mine.subtopicCodes()).containsExactly("FITNESS_YOGA");
    }

    @Test
    void 비활성_세부_주제와_비활성_세트는_거부한다() {
        jdbcTemplate.update("update interest_subtopic set active = false where subtopic_code = 'FITNESS_YOGA'");
        assertInvalid(List.of("FITNESS"), V, List.of("FITNESS_YOGA"));

        entityManager.clear();
        jdbcTemplate.update("update interest_subtopic_set set active = false");
        assertInvalid(List.of("FITNESS"), V, List.of("FITNESS_WEIGHTS"));
    }

    @Test
    void 활성_세부_세트는_하나만_둘_수_있다() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into interest_subtopic_set (subtopic_version, parent_taxonomy_version, subtopic_hash, active, created_at) "
                        + "values ('subtopic-x', 'v0.2', ?, true, UTC_TIMESTAMP(6))", "a".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void assertInvalid(List<String> parents, String version, List<String> codes) {
        assertThatThrownBy(() -> service.replace(memberId, "v0.2", parents, version, codes))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.VALIDATION_FAILED));
    }

    private int rowCount() {
        return jdbcTemplate.queryForObject(
                "select count(*) from member_interest_subtopic where member_id = ?", Integer.class, memberId);
    }

    private Instant selectedAt(String code) {
        return jdbcTemplate.queryForObject(
                "select selected_at from member_interest_subtopic where member_id = ? and subtopic_code = ?",
                Timestamp.class, memberId, code).toInstant();
    }
}
