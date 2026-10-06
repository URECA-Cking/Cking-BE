package kr.co.cking.interest.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 관심 분야 분류체계 한 버전이다. 등록한 버전의 해시와 분야 행은 수정하지 않는다. */
@Entity
@Table(name = "interest_taxonomy")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterestTaxonomy {

    @Id
    @Column(name = "taxonomy_version", length = 20, updatable = false)
    private String taxonomyVersion;

    @Column(name = "taxonomy_hash", nullable = false, updatable = false, length = 64)
    private String taxonomyHash;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public InterestTaxonomy(String taxonomyVersion, String taxonomyHash, boolean active, Instant createdAt) {
        this.taxonomyVersion = taxonomyVersion;
        this.taxonomyHash = taxonomyHash;
        this.active = active;
        this.createdAt = createdAt;
    }
}
