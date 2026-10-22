package kr.co.cking.interest.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 분류체계 버전에 속한 관심 분야다. {@code description}은 화면 노출용이 아니라 분류 기준문(해시 계산 대상)이다. */
@Entity
@Table(name = "interest_category")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterestCategory {

    @EmbeddedId
    private InterestCategoryId id;

    @Column(name = "name", nullable = false, updatable = false, length = 50)
    private String name;

    @Column(name = "description", nullable = false, updatable = false, length = 500)
    private String description;

    @Column(name = "display_order", nullable = false, updatable = false)
    private int displayOrder;

    @Column(name = "active", nullable = false)
    private boolean active;

    public InterestCategory(
            String taxonomyVersion,
            String interestCode,
            String name,
            String description,
            int displayOrder,
            boolean active
    ) {
        this.id = new InterestCategoryId(taxonomyVersion, interestCode);
        this.name = name;
        this.description = description;
        this.displayOrder = displayOrder;
        this.active = active;
    }

    public String getTaxonomyVersion() {
        return id.getTaxonomyVersion();
    }

    public String getInterestCode() {
        return id.getInterestCode();
    }
}
