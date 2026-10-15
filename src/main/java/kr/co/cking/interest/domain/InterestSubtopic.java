package kr.co.cking.interest.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 세부 주제 세트에 속한 세부 주제다. {@code description}은 화면 노출용이 아니라 해시 계산 대상 기준문이다. */
@Entity
@Table(name = "interest_subtopic")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterestSubtopic {

    @EmbeddedId
    private InterestSubtopicId id;

    @Column(name = "parent_taxonomy_version", nullable = false, updatable = false, length = 20)
    private String parentTaxonomyVersion;

    @Column(name = "parent_interest_code", nullable = false, updatable = false, length = 30)
    private String parentInterestCode;

    @Column(name = "name", nullable = false, updatable = false, length = 50)
    private String name;

    @Column(name = "description", nullable = false, updatable = false, length = 500)
    private String description;

    @Column(name = "display_order", nullable = false, updatable = false)
    private int displayOrder;

    @Column(name = "active", nullable = false)
    private boolean active;

    public InterestSubtopic(
            String subtopicVersion,
            String subtopicCode,
            String parentTaxonomyVersion,
            String parentInterestCode,
            String name,
            String description,
            int displayOrder,
            boolean active
    ) {
        this.id = new InterestSubtopicId(subtopicVersion, subtopicCode);
        this.parentTaxonomyVersion = parentTaxonomyVersion;
        this.parentInterestCode = parentInterestCode;
        this.name = name;
        this.description = description;
        this.displayOrder = displayOrder;
        this.active = active;
    }

    public String getSubtopicVersion() {
        return id.getSubtopicVersion();
    }

    public String getSubtopicCode() {
        return id.getSubtopicCode();
    }
}
