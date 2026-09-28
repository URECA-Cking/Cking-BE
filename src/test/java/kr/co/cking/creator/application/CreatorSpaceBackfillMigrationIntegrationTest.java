package kr.co.cking.creator.application;

import kr.co.cking.creator.domain.Creator;
import kr.co.cking.creator.repository.CreatorRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

/** V19 백필을 트랜잭션에서 재실행해 대상 선택과 멱등성을 검증한다. */
@SpringBootTest
@Transactional
class CreatorSpaceBackfillMigrationIntegrationTest {

    private static final ClassPathResource BACKFILL_SCRIPT =
            new ClassPathResource("db/migration/V19__backfill_creator_space.sql");

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private CreatorRepository creatorRepository;

    @Test
    void 스페이스가_없는_크리에이터만_활성_템플릿으로_채우고_재실행해도_중복되지_않는다() {
        Member admin = createMember("백필관리자", MemberRole.ADMIN);
        insertActiveTemplate(admin.getMemberId(), "creator-{creatorId}");
        Creator withoutSpace = createCreator("백필대상");
        Creator withSpace = createCreator("기존스페이스");
        jdbcTemplate.update("""
                INSERT INTO creator_space (creator_id, intro_text, profile_image_url, banner_image_url, slug,
                    home_tab_enabled, missions_tab_enabled, posts_tab_enabled, events_tab_enabled, created_at)
                VALUES (?, '기존 소개', 'p', 'b', ?, 1, 1, 1, 1, UTC_TIMESTAMP(6))
                """, withSpace.getCreatorId(), "existing-" + withSpace.getCreatorId());

        runBackfill();
        runBackfill();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT slug FROM creator_space WHERE creator_id = ?", String.class, withoutSpace.getCreatorId()))
                .isEqualTo("creator-" + withoutSpace.getCreatorId());
        assertThat(jdbcTemplate.queryForList(
                "SELECT intro_text FROM creator_space WHERE creator_id = ?", String.class, withSpace.getCreatorId()))
                .containsExactly("기존 소개");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM creator_space WHERE creator_id = ?", Long.class, withoutSpace.getCreatorId()))
                .isEqualTo(1L);
    }

    @Test
    void 활성_템플릿의_slugRule이_형식에_맞지_않으면_채우지_않는다() {
        Member admin = createMember("백필무효관리자", MemberRole.ADMIN);
        insertActiveTemplate(admin.getMemberId(), "creator-{creatorId}0");
        Creator creator = createCreator("백필무효대상");

        runBackfill();

        assertThat(countSpaces(creator.getCreatorId())).isZero();
    }

    @Test
    void 대문자가_섞인_slugRule도_채우지_않는다() {
        Member admin = createMember("백필대문자관리자", MemberRole.ADMIN);
        insertActiveTemplate(admin.getMemberId(), "Creator-{creatorId}");
        Creator creator = createCreator("백필대문자대상");

        runBackfill();

        assertThat(countSpaces(creator.getCreatorId())).isZero();
    }

    private void runBackfill() {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            ScriptUtils.executeSqlScript(connection, BACKFILL_SCRIPT);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private void insertActiveTemplate(Long adminId, String slugRule) {
        jdbcTemplate.update("""
                INSERT INTO creator_space_template (intro_text, profile_image_url, banner_image_url, slug_rule,
                    home_tab_enabled, missions_tab_enabled, posts_tab_enabled, events_tab_enabled,
                    active_marker, created_by, updated_by, created_at, updated_at)
                VALUES ('소개', 'p', 'b', ?, 1, 0, 1, 0, 1, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, slugRule, adminId, adminId);
    }

    private long countSpaces(Long creatorId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM creator_space WHERE creator_id = ?", Long.class, creatorId);
    }

    private Creator createCreator(String name) {
        Member member = createMember(name, MemberRole.USER);
        return creatorRepository.saveAndFlush(new Creator(member.getMemberId(), name));
    }

    private Member createMember(String name, MemberRole role) {
        return memberRepository.saveAndFlush(new Member(name, null, null, role));
    }
}
