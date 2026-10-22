package kr.co.cking.subscriptionverification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 같은 정규화 이미지 hash의 재사용 탐지를 직렬화하는 잠금 행이다. */
@Getter
@Entity
@Table(name = "subscription_verification_image_hash_lock")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubscriptionVerificationImageHashLock {

    @Id
    @Column(name = "image_sha256", length = 64)
    private String imageSha256;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
