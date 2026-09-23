package kr.co.cking.auth.repository;

import java.util.Optional;
import kr.co.cking.auth.domain.OAuthAccount;
import kr.co.cking.auth.domain.OAuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OAuthAccountRepository extends JpaRepository<OAuthAccount, Long> {

    Optional<OAuthAccount> findByProviderAndProviderUserId(OAuthProvider provider, String providerUserId);
}
