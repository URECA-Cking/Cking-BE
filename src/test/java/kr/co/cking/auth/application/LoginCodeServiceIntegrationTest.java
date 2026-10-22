package kr.co.cking.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import kr.co.cking.auth.application.dto.IssuedLoginCode;
import kr.co.cking.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 실제 Redis Lua 실행에서 Login Code가 한 번만 소비되는지 검증한다. */
@SpringBootTest
class LoginCodeServiceIntegrationTest {

    @Autowired
    private LoginCodeService loginCodeService;

    /** 동시에 교환해도 하나의 요청만 회원 ID를 받아가는지 검증한다. */
    @Test
    void 동일_LoginCode는_동시_소비해도_한번만_성공한다() throws Exception {
        IssuedLoginCode issued = loginCodeService.issue(91L);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Long> consumedMemberIds = completedResults(executor.invokeAll(List.of(
                    consume(issued.code(), issued.browserBinding()), consume(issued.code(), issued.browserBinding()))));

            assertThat(consumedMemberIds).containsExactly(91L);
        }
    }

    /** 다른 브라우저 값으로는 교환되지 않고, Code는 남아 발급받은 브라우저가 그대로 교환할 수 있는지 검증한다. */
    @Test
    void 다른_브라우저_값으로는_교환되지_않고_Code도_소비되지_않는다() throws Exception {
        IssuedLoginCode issued = loginCodeService.issue(92L);

        assertThat(consume(issued.code(), "other-browser").call()).isNull();
        assertThat(consume(issued.code(), issued.browserBinding()).call()).isEqualTo(92L);
        assertThat(consume(issued.code(), issued.browserBinding()).call()).isNull();
    }

    /** Login Code 소비 성공 여부를 null 또는 회원 ID로 바꾸는 병렬 작업을 만든다. */
    private Callable<Long> consume(String code, String browserBinding) {
        return () -> {
            try {
                return loginCodeService.consume(code, browserBinding);
            } catch (BusinessException exception) {
                return null;
            }
        };
    }

    /** 병렬 소비 작업의 완료 결과에서 성공한 회원 ID만 수집한다. */
    private List<Long> completedResults(List<Future<Long>> futures) throws Exception {
        java.util.ArrayList<Long> memberIds = new java.util.ArrayList<>();
        for (Future<Long> future : futures) {
            Long memberId = future.get();
            if (memberId != null) {
                memberIds.add(memberId);
            }
        }
        return memberIds;
    }
}
