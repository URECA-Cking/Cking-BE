package kr.co.cking.creator.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.creator.domain.CreatorErrorCode;
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
class CreatorApplicationConcurrencyIntegrationTest {

    @Autowired private CreatorApplicationService service;
    @Autowired private MemberRepository memberRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long memberId;

    @AfterEach
    void cleanUp() {
        if (memberId != null) {
            jdbcTemplate.update("DELETE FROM creator_application WHERE member_id = ?", memberId);
            jdbcTemplate.update("DELETE FROM member WHERE member_id = ?", memberId);
        }
    }

    @Test
    void simultaneousApplicationsReturnConcurrentCommandForOneRequest() throws Exception {
        memberId = memberRepository.saveAndFlush(new Member("동시신청", null, null, MemberRole.USER)).getMemberId();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int index = 0; index < 2; index++) {
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        service.apply(memberId);
                        return "SUCCESS";
                    } catch (BusinessException exception) {
                        return exception.getErrorCode().code();
                    }
                }));
            }
            start.countDown();
            List<String> codes = List.of(results.get(0).get(), results.get(1).get());

            assertThat(codes).containsExactlyInAnyOrder("SUCCESS", CreatorErrorCode.CONCURRENT_COMMAND.code());
        } finally {
            executor.shutdownNow();
        }
    }
}
