package kr.co.cking.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/** 외부 OAuth Identity와 Cking Member를 연결하는 불변 계정 연결 정보. */
@Getter
@Entity
@Table(
        name = "oauth_account",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_oauth_account_provider_user",
                columnNames = {"provider", "provider_user_id"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OAuthAccount {

    private static final int PROVIDER_USER_ID_MAX_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "oauth_account_id")
    private Long oauthAccountId;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false, length = 20)
    private OAuthProvider provider;

    @Column(name = "provider_user_id", nullable = false, updatable = false, length = PROVIDER_USER_ID_MAX_LENGTH)
    private String providerUserId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public OAuthAccount(Long memberId, OAuthProvider provider, String providerUserId) {
        if (memberId == null || memberId <= 0) {
            throw new IllegalArgumentException("memberId는 양수여야 합니다.");
        }
        if (provider == null) {
            throw new IllegalArgumentException("OAuth Provider는 필수입니다.");
        }
        if (providerUserId == null || providerUserId.isBlank()) {
            throw new IllegalArgumentException("providerUserId는 필수입니다.");
        }
        if (providerUserId.length() > PROVIDER_USER_ID_MAX_LENGTH) {
            throw new IllegalArgumentException("providerUserId는 255자 이하여야 합니다.");
        }

        this.memberId = memberId;
        this.provider = provider;
        this.providerUserId = providerUserId;
    }
}
