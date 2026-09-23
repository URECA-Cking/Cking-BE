package kr.co.cking.creator.application;

import kr.co.cking.creator.application.dto.CreatorSpaceTemplateFields;
import kr.co.cking.creator.domain.CreatorSpaceTemplate;
import kr.co.cking.creator.repository.CreatorSpaceTemplateRepository;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 활성 템플릿 전환이 실제 MySQL의 uk_creator_space_template_active UNIQUE 제약과
 * 충돌하지 않는지 검증한다. 각 서비스 호출이 실제로 커밋되어야 flush 순서 문제가
 * 재현되므로, 테스트 트랜잭션으로 묶어 롤백하는 방식(@Transactional)은 쓰지 않는다
 * — 같은 트랜잭션 안에서는 영속성 컨텍스트가 엔티티를 그대로 캐시해서 재조회가
 * DB를 거치지 않고, 커밋도 없어 문제의 flush 순서가 실제로 발생하지 않는다.
 */
@SpringBootTest
class CreatorSpaceTemplateServiceIntegrationTest {

    private static final CreatorSpaceTemplateFields FIELDS = new CreatorSpaceTemplateFields(
            "소개", "https://img/profile.png", "https://img/banner.png", "creator-{creatorId}", true, true, true, true
    );

    @Autowired
    private CreatorSpaceTemplateService service;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private CreatorSpaceTemplateRepository templateRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long adminId;

    @AfterEach
    void cleanUp() {
        if (adminId != null) {
            jdbcTemplate.update("DELETE FROM creator_space_template WHERE created_by = ?", adminId);
            jdbcTemplate.update("DELETE FROM member WHERE member_id = ?", adminId);
        }
    }

    @Test
    void reactivatingPreviousTemplateSucceedsWithoutUniqueConstraintViolation() {
        adminId = memberRepository.saveAndFlush(new Member("관리자", null, null, MemberRole.ADMIN)).getMemberId();
        Long templateAId = service.create(adminId, FIELDS).getTemplateId();
        Long templateBId = service.create(adminId, FIELDS).getTemplateId();

        service.activate(adminId, templateBId);

        assertThatCode(() -> service.activate(adminId, templateAId))
                .doesNotThrowAnyException();

        CreatorSpaceTemplate reloadedA = templateRepository.findById(templateAId).orElseThrow();
        CreatorSpaceTemplate reloadedB = templateRepository.findById(templateBId).orElseThrow();
        assertThat(reloadedA.isActive()).isTrue();
        assertThat(reloadedB.isActive()).isFalse();
    }
}
