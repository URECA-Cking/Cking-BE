package kr.co.cking.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** ID/PW로 인증하는 관리자 Member의 자격 증명 정보를 보관한다. */
@Getter
@Entity
@Table(
        name = "admin_account",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_admin_account_member", columnNames = "member_id"),
                @UniqueConstraint(name = "uk_admin_account_login_id", columnNames = "login_id")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminAccount {

    private static final int LOGIN_ID_MAX_LENGTH = 100;
    private static final int PASSWORD_HASH_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "admin_account_id")
    private Long adminAccountId;

    @Column(name = "member_id", nullable = false, updatable = false)
    private Long memberId;

    @Column(name = "login_id", nullable = false, updatable = false, length = LOGIN_ID_MAX_LENGTH)
    private String loginId;

    @Column(name = "password_hash", nullable = false, length = PASSWORD_HASH_MAX_LENGTH)
    private String passwordHash;

    @Column(name = "active", nullable = false)
    private boolean active;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 유효한 관리자 Member와 BCrypt 해시로 관리자 계정을 생성한다. */
    public AdminAccount(Long memberId, String loginId, String passwordHash) {
        if (memberId == null || memberId <= 0) {
            throw new IllegalArgumentException("memberId는 양수여야 합니다.");
        }
        if (loginId == null || loginId.isBlank() || loginId.length() > LOGIN_ID_MAX_LENGTH) {
            throw new IllegalArgumentException("loginId는 1자 이상 100자 이하여야 합니다.");
        }
        if (passwordHash == null || passwordHash.isBlank() || passwordHash.length() > PASSWORD_HASH_MAX_LENGTH) {
            throw new IllegalArgumentException("passwordHash는 1자 이상 100자 이하여야 합니다.");
        }

        this.memberId = memberId;
        this.loginId = loginId;
        this.passwordHash = passwordHash;
        this.active = true;
    }
}
