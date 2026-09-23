package kr.co.cking.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import kr.co.cking.auth.application.model.OAuthUserInfo;
import kr.co.cking.auth.domain.OAuthAccount;
import kr.co.cking.auth.domain.OAuthProvider;
import kr.co.cking.auth.repository.OAuthAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class OAuthLoginServiceTest {

    @Mock private OAuthAccountRepository oauthAccountRepository;
    @Mock private OAuthAccountCreationService oauthAccountCreationService;
    @InjectMocks private OAuthLoginService oauthLoginService;

    @Test
    void existingOAuthAccountReturnsConnectedMemberIdWithoutCreatingMember() {
        OAuthUserInfo userInfo = userInfo("google-sub-1", "기존 사용자");
        when(oauthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-1"))
                .thenReturn(java.util.Optional.of(new OAuthAccount(41L, OAuthProvider.GOOGLE, "google-sub-1")));

        Long memberId = oauthLoginService.login(userInfo);

        assertThat(memberId).isEqualTo(41L);
        verify(oauthAccountCreationService, never()).create(any(), any());
    }

    @Test
    void firstLoginCreatesMemberAndOAuthAccountWithNormalizedName() {
        OAuthUserInfo userInfo = userInfo("google-sub-2", "  새 사용자  ");
        when(oauthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-2"))
                .thenReturn(java.util.Optional.empty());
        when(oauthAccountCreationService.create(userInfo, "새 사용자")).thenReturn(42L);

        Long memberId = oauthLoginService.login(userInfo);

        assertThat(memberId).isEqualTo(42L);
        verify(oauthAccountCreationService).create(userInfo, "새 사용자");
    }

    @Test
    void uniqueConflictReturnsMemberIdOfConcurrentlyCreatedOAuthAccount() {
        OAuthUserInfo userInfo = userInfo("google-sub-3", "사용자");
        when(oauthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-3"))
                .thenReturn(
                        java.util.Optional.empty(),
                        java.util.Optional.of(new OAuthAccount(43L, OAuthProvider.GOOGLE, "google-sub-3"))
                );
        when(oauthAccountCreationService.create(userInfo, "사용자"))
                .thenThrow(new DataIntegrityViolationException("duplicate OAuth account"));

        assertThat(oauthLoginService.login(userInfo)).isEqualTo(43L);
    }

    @Test
    void unrelatedPersistenceFailureIsNotHiddenAsExistingOAuthAccount() {
        OAuthUserInfo userInfo = userInfo("google-sub-4", "사용자");
        DataIntegrityViolationException failure = new DataIntegrityViolationException("foreign key failure");
        when(oauthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-4"))
                .thenReturn(java.util.Optional.empty());
        when(oauthAccountCreationService.create(userInfo, "사용자")).thenThrow(failure);

        assertThatThrownBy(() -> oauthLoginService.login(userInfo)).isSameAs(failure);
    }

    @Test
    void normalizeNameUsesDefaultAndDoesNotSplitSurrogatePair() {
        assertThat(OAuthLoginService.normalizeName(null)).isEqualTo("사용자");
        assertThat(OAuthLoginService.normalizeName(" \t ")).isEqualTo("사용자");

        String longName = "😀".repeat(51);
        String normalized = OAuthLoginService.normalizeName(longName);
        assertThat(normalized.codePointCount(0, normalized.length())).isEqualTo(50);
        assertThat(normalized).doesNotEndWith("\uD83D");
    }

    private OAuthUserInfo userInfo(String providerUserId, String name) {
        return new OAuthUserInfo(OAuthProvider.GOOGLE, providerUserId, "same@example.com", name);
    }
}
