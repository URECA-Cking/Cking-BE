package kr.co.cking.creator.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.application.dto.CreatorSpaceTemplateFields;
import kr.co.cking.creator.domain.CreatorErrorCode;
import kr.co.cking.creator.domain.CreatorSpaceTemplate;
import kr.co.cking.member.domain.Member;
import kr.co.cking.member.domain.MemberRole;
import kr.co.cking.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CreatorSpaceTemplateConcurrencyIntegrationTest {

    private static final CreatorSpaceTemplateFields FIELDS = new CreatorSpaceTemplateFields(
            "소개", "https://img/profile.png", "https://img/banner.png", "creator-{creatorId}", true, true, true, true
    );

    @Autowired private CreatorSpaceTemplateService service;
    @Autowired private MemberRepository memberRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long adminId;

    @AfterEach
    void cleanUp() {
        if (adminId != null) {
            jdbcTemplate.update("DELETE FROM creator_space_template WHERE created_by = ?", adminId);
            jdbcTemplate.update("DELETE FROM member WHERE member_id = ?", adminId);
        }
    }

    @Test
    void simultaneousActivationsLeaveExactlyOneActiveTemplate() throws Exception {
        adminId = memberRepository.saveAndFlush(new Member("템플릿관리자", null, null, MemberRole.ADMIN)).getMemberId();
        Long firstId = service.create(adminId, FIELDS).getTemplateId();
        Long secondId = service.create(adminId, FIELDS).getTemplateId();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (Long templateId : List.of(firstId, secondId)) {
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        service.activate(adminId, templateId);
                        return "SUCCESS";
                    } catch (BusinessException exception) {
                        return exception.getErrorCode().code();
                    }
                }));
            }
            start.countDown();
            List<String> codes = List.of(results.get(0).get(), results.get(1).get());

            assertThat(codes).containsExactlyInAnyOrder("SUCCESS", CreatorErrorCode.CONCURRENT_COMMAND.code());
            Integer activeCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM creator_space_template WHERE created_by = ? AND active_marker = 1",
                    Integer.class, adminId);
            assertThat(activeCount).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }
}
