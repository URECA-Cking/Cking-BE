package kr.co.cking.interest.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;
import kr.co.cking.interest.application.dto.MemberInterests;
import kr.co.cking.interest.domain.InterestCategory;
import kr.co.cking.interest.domain.InterestTaxonomy;
import kr.co.cking.interest.domain.InterestTaxonomyHash;
import kr.co.cking.interest.domain.InterestTaxonomyHash.Row;
import kr.co.cking.interest.repository.InterestCategoryRepository;
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

/** 시드된 v0.2 분류체계(17개)로 관심 분야 저장·조회를 실제 MySQL에서 확인한다. 테스트마다 롤백한다. */
@SpringBootTest
@Transactional
class MemberInterestServiceIntegrationTest {

    @Autowired MemberInterestService service;
    @Autowired MemberRepository memberRepository;
    @Autowired InterestTaxonomyRepository taxonomyRepository;
    @Autowired InterestCategoryRepository categoryRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @PersistenceContext EntityManager entityManager;

    private Long memberId;

    @BeforeEach
    void setUp() {
        memberId = memberRepository.saveAndFlush(
                new Member("관심분야테스트-" + UUID.randomUUID().toString().substring(0, 8), null, null, MemberRole.USER))
                .getMemberId();
    }

    @Test
    void 선택이_없으면_활성_분류체계_버전과_빈_목록을_반환한다() {
        MemberInterests result = service.findMine(memberId);

        assertThat(result.taxonomyVersion()).isEqualTo("v0.2");
        assertThat(result.interestCodes()).isEmpty();
    }

    @Test
    void 요청_순서와_무관하게_분류체계_노출_순서로_반환한다() {
        // v0.2 노출 순서: FITNESS(1), FOOD(2), ..., TRAVEL(6)
        MemberInterests result = service.replace(memberId, "v0.2", List.of("TRAVEL", "FITNESS", "FOOD"));

        assertThat(result.taxonomyVersion()).isEqualTo("v0.2");
        assertThat(result.interestCodes()).containsExactly("FITNESS", "FOOD", "TRAVEL");
        assertThat(service.findMine(memberId).interestCodes()).containsExactly("FITNESS", "FOOD", "TRAVEL");
    }

    @Test
    void 선택은_0개_1개_3개까지_저장할_수_있다() {
        assertThat(service.replace(memberId, "v0.2", List.of()).interestCodes()).isEmpty();
        assertThat(service.replace(memberId, "v0.2", List.of("GAME")).interestCodes()).containsExactly("GAME");
        assertThat(service.replace(memberId, "v0.2", List.of("GAME", "MUSIC", "PET")).interestCodes())
                .containsExactly("GAME", "MUSIC", "PET");
    }

    @Test
    void 빈_배열로_저장하면_전체_해제한다() {
        service.replace(memberId, "v0.2", List.of("GAME", "MUSIC"));

        MemberInterests cleared = service.replace(memberId, "v0.2", List.of());

        assertThat(cleared.interestCodes()).isEmpty();
        assertThat(cleared.taxonomyVersion()).isEqualTo("v0.2");
        assertThat(countRows()).isZero();
    }

    @Test
    void 기존_선택을_요청_목록으로_교체한다() {
        service.replace(memberId, "v0.2", List.of("GAME", "MUSIC"));

        MemberInterests replaced = service.replace(memberId, "v0.2", List.of("MUSIC", "PET"));

        assertThat(replaced.interestCodes()).containsExactly("MUSIC", "PET");
        assertThat(countRows()).isEqualTo(2);
    }

    @Test
    void 같은_요청을_반복해도_최종_상태가_같고_유지되는_선택의_시각은_바뀌지_않는다() {
        service.replace(memberId, "v0.2", List.of("GAME"));
        Instant old = Instant.parse("2026-01-01T00:00:00Z");
        jdbcTemplate.update("update member_interest set selected_at = ? where member_id = ?",
                Timestamp.from(old), memberId);
        entityManager.clear();

        MemberInterests again = service.replace(memberId, "v0.2", List.of("GAME"));
        MemberInterests withNew = service.replace(memberId, "v0.2", List.of("GAME", "PET"));

        assertThat(again.interestCodes()).containsExactly("GAME");
        assertThat(withNew.interestCodes()).containsExactly("GAME", "PET");
        entityManager.flush();
        entityManager.clear();
        assertThat(selectedAt("GAME")).isEqualTo(old);
        assertThat(selectedAt("PET")).isAfter(old);
    }

    @Test
    void 선택_수가_상한을_넘거나_중복이면_거부하고_기존_선택을_유지한다() {
        service.replace(memberId, "v0.2", List.of("GAME"));

        assertValidationFailed(() -> service.replace(memberId, "v0.2", List.of("A", "B", "C", "D")));
        assertValidationFailed(() -> service.replace(memberId, "v0.2", List.of("GAME", "GAME")));

        assertThat(service.findMine(memberId).interestCodes()).containsExactly("GAME");
    }

    @Test
    void 없는_코드나_null은_거부한다() {
        assertValidationFailed(() -> service.replace(memberId, "v0.2", List.of("NOT_A_CATEGORY")));
        assertValidationFailed(() -> service.replace(memberId, "v0.2", java.util.Arrays.asList("GAME", null)));
        assertValidationFailed(() -> service.replace(memberId, "v0.2", null));
    }

    @Test
    void 비활성_분야는_고를_수_없다() {
        jdbcTemplate.update("update interest_category set active = false "
                + "where taxonomy_version = 'v0.2' and interest_code = 'PET'");

        assertValidationFailed(() -> service.replace(memberId, "v0.2", List.of("GAME", "PET")));
        assertThat(service.replace(memberId, "v0.2", List.of("GAME")).interestCodes()).containsExactly("GAME");
    }

    @Test
    void 등록되지_않았거나_활성이_아닌_분류체계_버전은_거부한다() {
        String inactive = "t" + UUID.randomUUID().toString().substring(0, 8);
        taxonomyRepository.saveAndFlush(new InterestTaxonomy(
                inactive, InterestTaxonomyHash.compute(List.of(new Row("X", "x", "x"))), false, Instant.now()));
        categoryRepository.saveAndFlush(new InterestCategory(inactive, "GAME", "게임", "설명", 1, true));

        assertValidationFailed(() -> service.replace(memberId, "v9.9", List.of("GAME")));
        assertValidationFailed(() -> service.replace(memberId, inactive, List.of("GAME")));
        assertValidationFailed(() -> service.replace(memberId, "v9.9", List.of()));
    }

    @Test
    void 존재하지_않는_회원은_RESOURCE_NOT_FOUND다() {
        assertThatThrownBy(() -> service.replace(Long.MAX_VALUE, "v0.2", List.of("GAME")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    private void assertValidationFailed(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.VALIDATION_FAILED));
    }

    private int countRows() {
        return jdbcTemplate.queryForObject(
                "select count(*) from member_interest where member_id = ?", Integer.class, memberId);
    }

    private Instant selectedAt(String code) {
        return jdbcTemplate.queryForObject(
                "select selected_at from member_interest where member_id = ? and interest_code = ?",
                Timestamp.class, memberId, code).toInstant();
    }
}
