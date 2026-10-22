package kr.co.cking.interest.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 세부 주제 세트 한 버전이다. 상위 분류체계 버전에 묶이며 등록한 세트의 해시와 행은 수정하지 않는다. */
@Entity
@Table(name = "interest_subtopic_set")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterestSubtopicSet {

    @Id
    @Column(name = "subtopic_version", length = 30, updatable = false)
    private String subtopicVersion;

    @Column(name = "parent_taxonomy_version", nullable = false, updatable = false, length = 20)
    private String parentTaxonomyVersion;

    @Column(name = "subtopic_hash", nullable = false, updatable = false, length = 64)
    private String subtopicHash;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public InterestSubtopicSet(
            String subtopicVersion, String parentTaxonomyVersion, String subtopicHash, boolean active, Instant createdAt
    ) {
        this.subtopicVersion = subtopicVersion;
        this.parentTaxonomyVersion = parentTaxonomyVersion;
        this.subtopicHash = subtopicHash;
        this.active = active;
        this.createdAt = createdAt;
    }
}
