package kr.co.cking.interest.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;

/** 관심 분야 식별자는 분류체계 버전과 코드의 조합이다. 같은 코드도 버전이 다르면 다른 분야다. */
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class InterestCategoryId implements Serializable {

    @Column(name = "taxonomy_version", length = 20)
    private String taxonomyVersion;

    @Column(name = "interest_code", length = 30)
    private String interestCode;
}
