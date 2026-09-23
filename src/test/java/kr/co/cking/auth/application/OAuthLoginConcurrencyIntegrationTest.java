package kr.co.cking.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import kr.co.cking.auth.application.model.OAuthUserInfo;
import kr.co.cking.auth.domain.OAuthAccount;
import kr.co.cking.auth.domain.OAuthProvider;
import kr.co.cking.auth.repository.OAuthAccountRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class OAuthLoginConcurrencyIntegrationTest {

    @Autowired private OAuthLoginService oauthLoginService;
    @Autowired private OAuthAccountRepository oauthAccountRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<OAuthUserInfo> createdUsers = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (OAuthUserInfo userInfo : createdUsers) {
            oauthAccountRepository.findByProviderAndProviderUserId(userInfo.provider(), userInfo.providerUserId())
                    .map(OAuthAccount::getMemberId)
                    .ifPresent(memberId -> {
                        jdbcTemplate.update(
                                "DELETE FROM oauth_account WHERE provider = ? AND provider_user_id = ?",
                                userInfo.provider().name(), userInfo.providerUserId());
                        jdbcTemplate.update("DELETE FROM member WHERE member_id = ?", memberId);
                    });
        }
    }

    @Test
    void concurrentFirstLoginCreatesOnlyOneMemberAndReturnsSameMemberId() throws Exception {
        String suffix = UUID.randomUUID().toString();
        OAuthUserInfo userInfo = new OAuthUserInfo(
                OAuthProvider.GOOGLE,
                "google-sub-" + suffix,
                "oauth-" + suffix + "@example.com",
                "동시 로그인"
        );
        createdUsers.add(userInfo);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> results = new ArrayList<>();

        try {
            for (int index = 0; index < 2; index++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return oauthLoginService.login(userInfo);
                }));
            }
            start.countDown();

            List<Long> memberIds = List.of(results.get(0).get(), results.get(1).get());
            assertThat(memberIds).hasSize(2).allSatisfy(memberId -> assertThat(memberId).isPositive());
            assertThat(memberIds).containsOnly(memberIds.getFirst());
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM oauth_account WHERE provider = ? AND provider_user_id = ?",
                    Integer.class,
                    OAuthProvider.GOOGLE.name(), userInfo.providerUserId())).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM member WHERE email = ?",
                    Integer.class,
                    userInfo.email())).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sameEmailFromDifferentProvidersCreatesSeparateMembers() {
        String suffix = UUID.randomUUID().toString();
        String sharedEmail = "shared-" + suffix + "@example.com";
        OAuthUserInfo google = new OAuthUserInfo(OAuthProvider.GOOGLE, "google-" + suffix, sharedEmail, "Google 사용자");
        OAuthUserInfo kakao = new OAuthUserInfo(OAuthProvider.KAKAO, "kakao-" + suffix, sharedEmail, "Kakao 사용자");
        createdUsers.addAll(List.of(google, kakao));

        Long googleMemberId = oauthLoginService.login(google);
        Long kakaoMemberId = oauthLoginService.login(kakao);

        assertThat(googleMemberId).isNotEqualTo(kakaoMemberId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM oauth_account WHERE member_id IN (?, ?)",
                Integer.class,
                googleMemberId, kakaoMemberId)).isEqualTo(2);
    }
}
